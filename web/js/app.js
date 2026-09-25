import { ClientePimg } from './pimg.js';
import { CacheTiles } from './cache.js';
import { Visor } from './visor.js';
import { mostrarPanel } from './panel.js';

const CACHE_MAX = 300;       // tiles decodificados en memoria (se informa en HELLO)
const THROTTLE_MS = 100;     // como máximo un VIEWPORT cada 100 ms

const selector = document.getElementById('selector');
const estadoEl = document.getElementById('estado');
const panelEl = document.getElementById('panel');

let imagenActual = null;
let epoca = 0;               // cambia con cada META: descarta decodificaciones de otra imagen
let porExpulsar = [];
let expulsadosTotal = 0;
let ultimoDone = '—';
let ultimoError = '—';
let estadoTexto = 'desconectado';

const cache = new CacheTiles(CACHE_MAX, clave => {
  porExpulsar.push(clave);
  expulsadosTotal++;
});
const visor = new Visor(document.getElementById('lienzo'), cache, () => programarVista());

const pimg = new ClientePimg({
  cacheMax: CACHE_MAX,

  alEstado: texto => {
    estadoTexto = texto;
    estadoEl.textContent = texto;
  },

  alLista: lista => {
    selector.replaceChildren();
    for (const img of lista) {
      const op = document.createElement('option');
      op.value = img.id;
      op.textContent = img.estado === 'READY' ? img.id : `${img.id} (${img.estado} ${img.progreso}%)`;
      op.disabled = img.estado !== 'READY';
      selector.appendChild(op);
    }
    const id = imagenActual ?? lista.find(i => i.estado === 'READY')?.id;
    if (id) {
      selector.value = id;
      abrir(id);             // también sirve para reabrir tras una reconexión
    }
  },

  alMeta: meta => {
    const mismaImagen = visor.meta?.id === meta.id;
    epoca++;
    cache.vaciar();          // el servidor empezó un registro nuevo: el cliente también
    porExpulsar = [];
    ultimaVista = '';
    visor.cargarImagen(meta, mismaImagen);
  },

  alTile: async (z, x, y, blob) => {
    const miEpoca = epoca;
    const bmp = await createImageBitmap(blob);   // decodifica fuera del hilo principal
    if (miEpoca !== epoca) { bmp.close(); return; }
    cache.poner(`${z},${x},${y}`, bmp);
    visor.tileLlego(`${z},${x},${y}`);
  },

  alDone: (seq, sent) => { ultimoDone = `seq ${seq}: ${sent} tiles`; },
  alError: c => { ultimoError = `${c.CODE} ${c.MSG}`; },
});

function abrir(id) {
  imagenActual = id;
  pimg.abrir(id);
}
selector.addEventListener('change', () => abrir(selector.value));

// ---------- Throttling de VIEWPORT ----------
let ultimoEnvio = 0;
let envioProgramado = null;
let ultimaVista = '';

function programarVista() {
  if (envioProgramado) return;
  const espera = Math.max(0, THROTTLE_MS - (performance.now() - ultimoEnvio));
  envioProgramado = setTimeout(() => {
    envioProgramado = null;
    enviarVista();
  }, espera);
}

function enviarVista() {
  const v = visor.vistaActual();
  if (!v) return;
  const firma = `${v.z}|${v.x}|${v.y}|${v.vw}|${v.vh}`;
  if (firma === ultimaVista) return;            // nada cambió
  ultimaVista = firma;
  if (porExpulsar.length) {                     // primero EVICT, para que el servidor pueda reenviarlos
    pimg.evict(porExpulsar);
    porExpulsar = [];
  }
  pimg.pedirVista(v.z, v.x, v.y, v.vw, v.vh);
  ultimoEnvio = performance.now();
}

// ---------- Panel de depuración ----------
setInterval(() => {
  const m = visor.meta;
  const bytesOriginal = m ? m.ancho * m.alto * 3 : 0;   // imagen sin comprimir (RGB)
  mostrarPanel(panelEl, {
    'Conexion': estadoTexto,
    'Imagen': m ? `${m.id} (${m.ancho} x ${m.alto})` : '—',
    'Nivel': m ? `${visor.z} de 0..${m.niveles - 1}` : '—',
    'Zoom': m ? `${(visor.zoom * 100).toFixed(1)} % del original` : '—',
    'SEQ actual': pimg.seq,
    'Tiles en memoria': `${cache.tamanio} / ${CACHE_MAX}`,
    'Memoria de tiles': `${(cache.bytesEstimados() / 2 ** 20).toFixed(1)} MB`,
    'Faltan en pantalla': visor.faltantes,
    'Tiles recibidos': pimg.stats.tiles,
    'Bytes recibidos': `${(pimg.stats.bytes / 2 ** 20).toFixed(2)} MB`,
    'vs. imagen original': m ? `${(100 * pimg.stats.bytes / bytesOriginal).toFixed(3)} %` : '—',
    'Expulsados (EVICT)': expulsadosTotal,
    'Descartados / CRC malo': `${pimg.stats.descartados} / ${pimg.stats.crcMalos}`,
    'Ultimo DONE': ultimoDone,
    'Ultimo error': ultimoError,
  });
}, 250);

pimg.conectar();