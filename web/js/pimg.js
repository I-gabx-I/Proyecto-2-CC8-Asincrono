import { crc32 } from './crc32.js';

const CABECERA = 28;                // PROTOCOLO.md §8.1

/** Cliente del protocolo PIMG sobre WebSocket (PROTOCOLO.md §5 y §6). */
export class ClientePimg {
  constructor(eventos) {
    this.ev = eventos;
    this.ws = null;
    this.seq = 0;                     // estrictamente creciente por conexión
    this.seqInicioImagen = Infinity;  // tiles con SEQ menor son de una imagen anterior
    this.intentos = 0;
    this.ultimoNum = 0;               // último NUM recibido en esta conexión (§8.3)
    this.stats = { bytes: 0, tiles: 0, crcMalos: 0, descartados: 0, perdidos: 0 };
  }

  conectar() {
    // Mismo servidor que entregó la página: ninguna petición externa
    const ws = new WebSocket(`ws://${location.host}/ws`, 'pimg.v2');
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      this.intentos = 0;
      this.ultimoNum = 0;             // conexión nueva: el servidor empieza otra vez en NUM = 1
      this.ev.alEstado('conectado');
      this.enviar(`HELLO|V:2|CACHE:${this.ev.cacheMax}`);
    };
    ws.onmessage = e => (typeof e.data === 'string' ? this.alTexto(e.data) : this.alBinario(e.data));
    ws.onclose = () => {
      this.ws = null;
      this.reconectar();
    };
    this.ws = ws;
  }

  /** Backoff exponencial: 1, 2, 4, 8 ... hasta 30 s (PROTOCOLO.md §9). */
  reconectar() {
    const segundos = Math.min(30, 2 ** this.intentos);
    this.intentos++;
    this.ev.alEstado(`desconectado, reintento en ${segundos} s`);
    setTimeout(() => this.conectar(), segundos * 1000);
  }

  enviar(texto) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(texto);
  }

  // ---------- Comandos cliente -> servidor ----------

  abrir(id) {
    this.seqInicioImagen = this.seq + 1;
    this.enviar(`OPEN|IMG:${id}`);
  }

  pedirVista(z, x, y, vw, vh) {
    this.seq++;
    this.enviar(`VIEWPORT|SEQ:${this.seq}|Z:${z}|X:${x}|Y:${y}|VW:${vw}|VH:${vh}`);
  }

  simular(perd, bw, lat) {
    this.enviar(`SIM|PERD:${perd}|BW:${bw}|LAT:${lat}`);
  }

  pedirTile(z, x, y) {
    this.enviar(`GET_TILE|SEQ:${this.seq}|Z:${z}|X:${x}|Y:${y}`);
  }

  evict(claves) {
    for (let i = 0; i < claves.length; i += 500) {   // respeta el máximo de 16 KB por mensaje
      this.enviar(`EVICT|TILES:${claves.slice(i, i + 500).join(';')}`);
    }
  }

  // ---------- Mensajes servidor -> cliente ----------

  alTexto(texto) {
    const [comando, ...partes] = texto.split('|');
    const c = {};
    for (const p of partes) {
      const i = p.indexOf(':');                 // el valor empieza tras el PRIMER ':'
      c[p.slice(0, i)] = p.slice(i + 1);
    }
    switch (comando) {
      case 'HELLO_OK':
        this.enviar('LIST');
        break;
      case 'LIST_RESP': {
        const lista = !c.IMGS ? [] : c.IMGS.split(';').map(s => {
          const [id, estado, progreso] = s.split(',');
          return { id, estado, progreso: Number(progreso) };
        });
        this.ev.alLista(lista);
        break;
      }
      case 'META':
        this.ev.alMeta({ id: c.IMG, ancho: +c.W, alto: +c.H, tile: +c.TS, niveles: +c.L, formato: c.FMT });
        break;
      case 'DONE':
        this.ev.alDone(+c.SEQ, +c.SENT);
        break;
      case 'SIM_OK':
        this.ev.alSim({ perdida: +c.PERD, ancho: +c.BW, latencia: +c.LAT });
        break;
      case 'ERROR':
        console.warn('PIMG ERROR', c.CODE, c.MSG);
        this.ev.alError(c);
        break;
    }
  }

  alBinario(buf) {
    this.stats.bytes += buf.byteLength;
    if (buf.byteLength < CABECERA) { this.stats.descartados++; return; }

    const v = new DataView(buf);                 // DataView lee big-endian por defecto
    const ver = v.getUint8(0), tipo = v.getUint8(1);
    const seq = v.getUint32(2), num = v.getUint32(6);
    if (ver !== 2) { this.stats.descartados++; return; }

    // NUM (§8.3) se revisa ANTES que lo demás: un mensaje que llegó no es una pérdida de red,
    // aunque después se descarte por CRC o por ser de otra imagen (esos tienen su propio contador)
    if (num > this.ultimoNum + 1) this.stats.perdidos += num - this.ultimoNum - 1;
    if (num > this.ultimoNum) this.ultimoNum = num;

    if (tipo !== 1) { this.stats.descartados++; return; }      // PARIDAD (0x02) llega en la Fase 6
    const z = v.getUint8(10), x = v.getUint32(11), y = v.getUint32(15);
    const fmt = v.getUint8(19), largo = v.getUint32(20), crc = v.getUint32(24);

    // Validaciones del receptor (PROTOCOLO.md §8.4)
    if ((fmt !== 1 && fmt !== 2) || CABECERA + largo !== buf.byteLength) {
      this.stats.descartados++;
      return;
    }
    if (seq < this.seqInicioImagen) {            // tile de una imagen anterior
      this.stats.descartados++;
      return;
    }
    const datos = new Uint8Array(buf, CABECERA, largo);
    if (crc32(datos) !== crc) {                  // integridad extremo a extremo
      this.stats.crcMalos++;
      this.pedirTile(z, x, y);                   // GET_TILE se retira en la Fase 8
      return;
    }
    this.stats.tiles++;
    this.ev.alTile(z, x, y, new Blob([datos], { type: fmt === 1 ? 'image/jpeg' : 'image/png' }));
  }
}