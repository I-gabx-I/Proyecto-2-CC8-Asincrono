import { crc32 } from './crc32.js';
import { ReceptorFec } from './transporte/fec.js';
import { Reportero } from './transporte/reportes.js';
import { FiltroBloom, M, K } from './transporte/bloom.js';

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
    this.stats = { bytes: 0, tiles: 0, crcMalos: 0, descartados: 0, perdidos: 0,
                   paridades: 0, recuperados: 0, irrecuperables: 0, filtros: 0 };
    this.fec = new ReceptorFec(32);   // últimos 32 tiles recibidos, para reconstruir (§11.5)
    this.reportero = new Reportero(); // mediciones para REPORT (§12.2)
    this.temporizador = null;
    this.rpt = 100;                   // ms entre REPORT (lo informa HELLO_OK)
    this.sem = 0;                     // semilla de los hashes del filtro (§13.7)
    this.cambios = false;             // la caché cambió desde el último filtro
    this.expulsiones = false;         // ...y fue por expulsiones
  }

  conectar() {
    // Mismo servidor que entregó la página: ninguna petición externa
    const ws = new WebSocket(`ws://${location.host}/ws`, 'pimg.v2');
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      this.intentos = 0;
      this.ultimoNum = 0;             // conexión nueva: el servidor empieza otra vez en NUM = 1
      this.fec.vaciar();
      this.reportero.reiniciar();
      this.ev.alEstado('conectado');
      this.enviar(`HELLO|V:2|CACHE:${this.ev.cacheMax}`);
    };
    ws.onmessage = e => (typeof e.data === 'string' ? this.alTexto(e.data) : this.alBinario(e.data));
    ws.onclose = () => {
      this.ws = null;
      clearInterval(this.temporizador);
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
    this.fec.vaciar();                // las claves z,x,y de otra imagen no sirven
    this.enviar(`OPEN|IMG:${id}`);
  }

  /** RESUME (§13.6): misma imagen tras reconectar; el filtro dice qué conserva la caché. */
  reanudar(id) {
    this.seqInicioImagen = this.seq + 1;
    this.fec.vaciar();
    const f = this.armarFiltro();
    this.enviar(`RESUME|IMG:${id}|SEM:${this.sem}|BITS:${f.base64()}`);
  }

  /** BLOOM (§13.4): estado completo de la caché, fechado con el último NUM recibido. */
  enviarBloom() {
    const max = this.ultimoNum;       // todo lo de NUM ≤ max ya está en caché o decodificándose
    const f = this.armarFiltro();
    this.stats.filtros++;
    this.enviar(`BLOOM|MAX:${max}|SEM:${this.sem}|BITS:${f.base64()}`);
  }

  /** Se reconstruye completo cada vez: un filtro de Bloom no permite borrar (§13.5). */
  armarFiltro() {
    const f = new FiltroBloom(this.sem);
    for (const clave of this.ev.clavesEnPoder()) {
      const [z, x, y] = clave.split(',').map(Number);
      f.agregar(z, x, y);
    }
    this.cambios = false;
    this.expulsiones = false;
    return f;
  }

  marcarCambio(expulsion) {
    this.cambios = true;
    if (expulsion) this.expulsiones = true;
  }

  /** Otra semilla mueve todas las posiciones: elimina un falso positivo persistente (§13.7). */
  cambiarSemilla() {
    this.sem = (this.sem + 1) >>> 0;
  }

  pedirVista(z, x, y, vw, vh) {
    this.seq++;
    this.enviar(`VIEWPORT|SEQ:${this.seq}|Z:${z}|X:${x}|Y:${y}|VW:${vw}|VH:${vh}`);
  }

  /** Mientras haya una imagen abierta: REPORT cada RPT ms (§12.2) y BLOOM cada 1 s si la caché cambió (§13.5). */
  iniciarReportes() {
    clearInterval(this.temporizador);
    let tics = 0;
    this.temporizador = setInterval(() => {
      this.enviar(this.reportero.mensaje(this.ultimoNum, this.stats.perdidos, this.stats.recuperados));
      if (++tics % Math.round(1000 / this.rpt) === 0 && this.cambios) this.enviarBloom();
    }, this.rpt);
  }

  simular(perd, bw, lat) {
    this.enviar(`SIM|PERD:${perd}|BW:${bw}|LAT:${lat}`);
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
        this.rpt = +c.RPT || 100;
        if (+c.BM !== M || +c.BK !== K) console.warn('PIMG: parametros del filtro distintos', c.BM, c.BK);
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
        this.iniciarReportes();
        this.ev.alMeta({ id: c.IMG, ancho: +c.W, alto: +c.H, tile: +c.TS, niveles: +c.L, formato: c.FMT,
                         reanudada: c.RES === '1' });
        break;
      case 'DONE':
        this.ev.alDone(+c.SEQ, +c.SENT, +c.PAR);
        break;
      case 'CTRL':
        this.ev.alCtrl({ r: +c.R, q: +c.Q, e: +c.E, tarde: +c.TARDE });
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

    this.reportero.llegada(performance.now());
    const v = new DataView(buf);                 // DataView lee big-endian por defecto
    const ver = v.getUint8(0), tipo = v.getUint8(1);
    const seq = v.getUint32(2), num = v.getUint32(6);
    if (ver !== 2) { this.stats.descartados++; return; }

    // NUM (§8.3) se revisa ANTES que lo demás: un mensaje que llegó no es una pérdida de red,
    // aunque después se descarte por CRC o por ser de otra imagen (esos tienen su propio contador)
    if (num > this.ultimoNum + 1) this.stats.perdidos += num - this.ultimoNum - 1;
    if (num > this.ultimoNum) this.ultimoNum = num;

    if (tipo === 2) { this.alParidad(v, buf, seq); return; }
    if (tipo !== 1) { this.stats.descartados++; return; }
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
      // No se pide de nuevo: lo reconstruye FEC (§11.5) o vuelve con la re-declaración (§15)
      return;
    }
    this.stats.tiles++;
    this.fec.guardar(`${z},${x},${y}`, datos);
    this.ev.alTile(z, x, y, new Blob([datos], { type: fmt === 1 ? 'image/jpeg' : 'image/png' }));
  }

  /** PARIDAD (§8.2): si falta exactamente un miembro de su grupo, lo reconstruye (§11.5). */
  alParidad(v, buf, seq) {
    if (seq < this.seqInicioImagen) { this.stats.descartados++; return; }   // de una imagen anterior
    const k = v.getUint8(10);
    if (k < 2 || k > 4 || buf.byteLength < 19 + 18 * k) { this.stats.descartados++; return; }

    const miembros = [];
    for (let i = 0; i < k; i++) {
      const o = 11 + 18 * i;
      const z = v.getUint8(o), x = v.getUint32(o + 1), y = v.getUint32(o + 5);
      miembros.push({ clave: `${z},${x},${y}`, z, x, y,
                      fmt: v.getUint8(o + 9), largo: v.getUint32(o + 10), crc: v.getUint32(o + 14) });
    }
    const o = 11 + 18 * k;
    const largoP = v.getUint32(o), crcP = v.getUint32(o + 4);
    if (19 + 18 * k + largoP !== buf.byteLength) { this.stats.descartados++; return; }
    const datosP = new Uint8Array(buf, o + 8, largoP);
    if (crc32(datosP) !== crcP) { this.stats.crcMalos++; return; }

    this.stats.paridades++;
    const r = this.fec.procesar(miembros, datosP);
    if (r.estado === 'recuperado') {
      const t = r.tile;
      this.stats.recuperados++;
      this.fec.guardar(t.clave, r.datos);
      this.ev.alTile(t.z, t.x, t.y, new Blob([r.datos], { type: t.fmt === 1 ? 'image/jpeg' : 'image/png' }));
    } else if (r.estado !== 'innecesaria') {
      this.stats.irrecuperables++;     // 2 o más faltantes, o la reconstrucción no pasó el CRC
    }
  }
}