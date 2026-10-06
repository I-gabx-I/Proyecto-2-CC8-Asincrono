import { ClientePimg } from './pimg.js';
import { CacheTiles } from './cache.js';
import { Visor } from './visor.js';
import { mostrarPanel } from './panel.js';
import { GraficaControl } from './grafica.js';

const CACHE_MAX = 300;       // tiles decodificados en memoria (se informa en HELLO)
const THROTTLE_MS = 100;     // como máximo un VIEWPORT cada 100 ms

const selector = document.getElementById('selector');
const estadoEl = document.getElementById('estado');
const panelEl = document.getElementById('panel');

let imagenActual = null;
let epoca = 0;               // cambia con cada META: descarta decodificaciones de otra imagen
let expulsadosTotal = 0;
const pendientes = new Set();          // recibidos que todavía se decodifican: también cuentan como "los tengo"
const MAX_REDECLARACIONES = 3;         // §15: máximo de intentos por vista; el 3.º con otra semilla
let intentosRedeclaracion = 0;
let redeclaraciones = 0;
let ultimoDone = '—';
let ultimoError = '—';
let estadoTexto = 'desconectado';
let lentoMs = 0;                       // "cliente lento": retardo artificial por tile (§16)
let turnoDecodificacion = Promise.resolve();
const simEstadoEl = document.getElementById('simEstado');
const Q_OBJETIVO = 8;                  // Q* del controlador PI (§12.3)
const grafica = new GraficaControl(document.getElementById('grafica'));
const ctrlTextoEl = document.getElementById('ctrlTexto');
let ultimoCtrl = null;

const cache = new CacheTiles(CACHE_MAX, () => {
  expulsadosTotal++;
  pimg.marcarCambio(true);             // antes del próximo VIEWPORT va un filtro nuevo (§13.5)
});
const visor = new Visor(document.getElementById('lienzo'), cache, () => programarVista());
const coordEl = document.getElementById('coord');
visor.onCursor = p => {
  coordEl.textContent = p ? `x ${p.x.toLocaleString('es')} · y ${p.y.toLocaleString('es')}` : '';
};

const pimg = new ClientePimg({
  cacheMax: CACHE_MAX,
  clavesEnPoder: () => [...cache.claves(), ...pendientes],

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
      abrir(id, true);       // también sirve para reabrir tras una reconexión
    }
  },

  alMeta: meta => {
    const mismaImagen = visor.meta?.id === meta.id;
    ultimaVista = '';
    intentosRedeclaracion = 0;
    if (meta.reanudada) {    // RESUME (§13.6): se conserva la caché; el servidor ya sabe qué tiene
      visor.cargarImagen(meta, true);
      return;
    }
    epoca++;
    cache.vaciar();          // imagen nueva: el servidor parte de un filtro vacío y el cliente también
    visor.cargarImagen(meta, mismaImagen);
  },

  alTile: (z, x, y, blob) => {
    const miEpoca = epoca;
    const clave = `${z},${x},${y}`;
    pendientes.add(clave);
    pimg.marcarCambio(false);
    pimg.reportero.inicioDecodificacion();       // COLA del REPORT: recibido, sin decodificar
    const procesar = async () => {
      const t0 = performance.now();
      try {
        if (lentoMs > 0) await new Promise(r => setTimeout(r, lentoMs));
        const bmp = await createImageBitmap(blob);   // decodifica fuera del hilo principal
        if (miEpoca !== epoca) { bmp.close(); return; }
        cache.poner(`${z},${x},${y}`, bmp);
        visor.tileLlego(`${z},${x},${y}`);
      } finally {
        pendientes.delete(clave);
        pimg.reportero.finDecodificacion(performance.now() - t0);
      }
    };
    if (lentoMs > 0) {
      // Cliente lento: de uno en uno, como un cliente que no da abasto (se acumulan)
      turnoDecodificacion = turnoDecodificacion.then(procesar).catch(e => console.warn(e));
    } else {
      procesar();
    }
  },

  alDone: (seq, sent, par) => {
    ultimoDone = `seq ${seq}: ${sent} tiles + ${par || 0} paridades`;
    if (seq === pimg.seq) setTimeout(() => revisarVista(seq), 250);   // §15: ¿quedó completa la vista?
  },
  alError: c => { ultimoError = `${c.CODE} ${c.MSG}`; },
  alCtrl: c => {
    ultimoCtrl = c;
    grafica.agregar(c.r, c.q);
  },
  alSim: s => {
    simEstadoEl.textContent = `aplicado: ${s.perdida} % · ${s.ancho || '∞'} KB/s · ${s.latencia} ms`;
  },
});

function abrir(id, reconexion = false) {
  imagenActual = id;
  if (reconexion && visor.meta?.id === id && cache.tamanio > 0) {
    pimg.reanudar(id);       // RESUME: la caché sigue en memoria (§13.6)
  } else {
    pimg.abrir(id);
  }
}

/**
 * Re-declaración de vista (§15). Tras el DONE de la vista vigente, si faltan tiles en pantalla y no hay
 * nada decodificándose, el cliente vuelve a declarar su ESTADO (filtro) y su VISTA (mismo VIEWPORT, SEQ
 * nuevo). No dice qué tiles faltan: el servidor decide con las reglas de siempre. No es un NACK.
 */
function revisarVista(seq) {
  if (seq !== pimg.seq || pendientes.size > 0 || visor.faltantes === 0) return;
  if (intentosRedeclaracion >= MAX_REDECLARACIONES) return;   // se espera al próximo movimiento
  const v = visor.vistaActual();
  if (!v) return;
  intentosRedeclaracion++;
  if (intentosRedeclaracion === MAX_REDECLARACIONES) pimg.cambiarSemilla();   // ¿falso positivo? (§13.7)
  redeclaraciones++;
  pimg.enviarBloom();
  pimg.pedirVista(v.z, v.x, v.y, v.vw, v.vh);
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
  intentosRedeclaracion = 0;                    // vista nueva del usuario
  if (pimg.expulsiones) pimg.enviarBloom();     // §13.5: primero el filtro, si hubo expulsiones
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
    'Ultimo NUM': pimg.ultimoNum,
    'Perdidos (saltos de NUM)': pimg.stats.perdidos,
    'Paridades recibidas': pimg.stats.paridades,
    'Recuperados por FEC (REC)': pimg.stats.recuperados,
    'No recuperables por FEC': pimg.stats.irrecuperables,
    'Tasa R (PI)': ultimoCtrl ? (ultimoCtrl.r ? `${ultimoCtrl.r} msg/s` : 'sin control (--sin-pi)') : '—',
    'Ocupacion Q / objetivo': ultimoCtrl ? `${ultimoCtrl.q} / ${Q_OBJETIVO} tiles` : '—',
    'TARDE (plazos incumplidos)': ultimoCtrl ? `${ultimoCtrl.tarde} %` : '—',
    'Bytes recibidos': `${(pimg.stats.bytes / 2 ** 20).toFixed(2)} MB`,
    'vs. imagen original': m ? `${(100 * pimg.stats.bytes / bytesOriginal).toFixed(3)} %` : '—',
    'Expulsados de la cache': expulsadosTotal,
    'Filtros BLOOM enviados (SEM)': `${pimg.stats.filtros} (SEM ${pimg.sem})`,
    'Re-declaraciones de vista': redeclaraciones,
    'Descartados / CRC malo': `${pimg.stats.descartados} / ${pimg.stats.crcMalos}`,
    'Ultimo DONE': ultimoDone,
    'Ultimo error': ultimoError,
  });
  const escala = grafica.dibujar(Q_OBJETIVO);
  ctrlTextoEl.textContent = `R: 0–${Math.round(escala.maxR)} msg/s · Q: 0–${Math.round(escala.maxQ)} tiles · punteada: Q* = ${Q_OBJETIVO}`;
}, 250);

// ---------- Red simulada y cliente lento ----------
document.getElementById('simAplicar').addEventListener('click', () => {
  pimg.simular(+document.getElementById('simPerd').value,
               +document.getElementById('simBw').value,
               +document.getElementById('simLat').value);
});
document.getElementById('lento').addEventListener('change', e => {
  lentoMs = Math.max(0, +e.target.value);
});

pimg.conectar();