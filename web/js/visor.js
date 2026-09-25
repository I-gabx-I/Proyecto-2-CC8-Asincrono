const FUNDIDO_MS = 150;          // duración del fundido de un tile nuevo
const SUAVIZADO = 0.2;           // fracción del camino de zoom recorrida en cada cuadro

/** Cámara continua, dibujo en canvas y entrada del usuario. */
export class Visor {
  constructor(canvas, cache, alCambiarVista) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.cache = cache;
    this.alCambiarVista = alCambiarVista;
    this.meta = null;

    // Cámara en coordenadas de la imagen ORIGINAL (nivel máximo)
    this.cx = 0;                 // punto de la imagen en el centro de la pantalla
    this.cy = 0;
    this.zoom = 1;               // píxeles de pantalla por píxel original (valor animado)
    this.zoomObjetivo = 1;       // hacia dónde se anima
    this.ancla = null;           // punto de pantalla que queda fijo al hacer zoom (el cursor)
    this.z = 0;                  // nivel de tiles en uso (se deduce del zoom)

    this.faltantes = 0;
    this.cuadricula = false;
    this.llegadas = new Map();   // clave -> momento de llegada (para el fundido)
    this.dibujoPendiente = false;
    this.ajustarTamanio();
    this.registrarEntrada();
  }

  // ---------- Geometría de la pirámide ----------

  anchoNivel(z) { return Math.ceil(this.meta.ancho / 2 ** (this.meta.niveles - 1 - z)); }
  altoNivel(z)  { return Math.ceil(this.meta.alto  / 2 ** (this.meta.niveles - 1 - z)); }
  factorNivel(z) { return 2 ** (z - (this.meta.niveles - 1)); }   // tamaño del nivel z / original

  /** Nivel cuyo tamaño en pantalla queda entre 0.71x y 1.41x de su tamaño real. */
  nivelPara(zoom) {
    const zMax = this.meta.niveles - 1;
    return Math.min(zMax, Math.max(0, zMax + Math.round(Math.log2(zoom))));
  }

  limitarZoom(zoom) {
    const minimo = Math.min(this.w / this.meta.ancho, this.h / this.meta.alto) / 2;
    return Math.min(1, Math.max(minimo, zoom));    // máximo 1:1 con el original: sin detalle inventado
  }

  /** Vista exacta en píxeles del nivel z, y escala d con la que se dibuja ese nivel. */
  geometria() {
    const f = this.factorNivel(this.z);
    const d = this.zoom / f;                       // píxeles de pantalla por píxel del nivel z
    return {
      d,
      X: (this.cx - this.w / (2 * this.zoom)) * f,
      Y: (this.cy - this.h / (2 * this.zoom)) * f,
      VW: this.w / d,
      VH: this.h / d,
    };
  }

  cargarImagen(meta, conservarPosicion) {
    this.meta = meta;
    if (!conservarPosicion) {
      this.zoom = this.zoomObjetivo = Math.min(this.w / meta.ancho, this.h / meta.alto) * 0.95;
      this.cx = meta.ancho / 2;
      this.cy = meta.alto / 2;
    }
    this.cambio();
  }

  /** Lo que se envía en VIEWPORT (PROTOCOLO.md §4.3): píxeles enteros del nivel z. */
  vistaActual() {
    if (!this.meta) return null;
    const g = this.geometria();
    return {
      z: this.z,
      x: Math.round(g.X),
      y: Math.round(g.Y),
      vw: Math.min(4096, Math.ceil(g.VW)),
      vh: Math.min(4096, Math.ceil(g.VH)),
    };
  }

  tilesVisibles(g) {
    const T = this.meta.tile, z = this.z;
    const cols = Math.ceil(this.anchoNivel(z) / T), filas = Math.ceil(this.altoNivel(z) / T);
    const x0 = Math.max(0, Math.floor(g.X / T));
    const x1 = Math.min(cols - 1, Math.floor((g.X + g.VW - 1) / T));
    const y0 = Math.max(0, Math.floor(g.Y / T));
    const y1 = Math.min(filas - 1, Math.floor((g.Y + g.VH - 1) / T));
    const tiles = [];
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) tiles.push({ x, y });
    return tiles;
  }

  // ---------- Llegada de tiles ----------

  tileLlego(clave) {
    const ahora = performance.now();
    this.llegadas.set(clave, ahora);
    if (this.llegadas.size > 500) {                // limpiar registros de fundidos ya terminados
      for (const [k, t] of this.llegadas) if (ahora - t > FUNDIDO_MS) this.llegadas.delete(k);
    }
    this.solicitarDibujo();
  }

  // ---------- Dibujo ----------

  solicitarDibujo() {
    if (this.dibujoPendiente) return;
    this.dibujoPendiente = true;
    requestAnimationFrame(() => { this.dibujoPendiente = false; this.dibujar(); });
  }

  dibujar() {
    const ctx = this.ctx;
    ctx.fillStyle = '#0b0d10';
    ctx.fillRect(0, 0, this.w, this.h);
    if (!this.meta) return;

    const animando = this.avanzarAnimacion();
    const g = this.geometria();
    const T = this.meta.tile, z = this.z;
    const anchoZ = this.anchoNivel(z), altoZ = this.altoNivel(z);
    const ahora = performance.now();
    let faltantes = 0, fundiendo = false;

    for (const t of this.tilesVisibles(g)) {
      const tw = Math.min(T, anchoZ - t.x * T);   // tiles del borde: más chicos
      const th = Math.min(T, altoZ - t.y * T);
      // Bordes redondeados en pantalla: evita rendijas de 1 px entre tiles vecinos
      const x0 = Math.round((t.x * T - g.X) * g.d);
      const y0 = Math.round((t.y * T - g.Y) * g.d);
      const x1 = Math.round((t.x * T + tw - g.X) * g.d);
      const y1 = Math.round((t.y * T + th - g.Y) * g.d);

      const clave = `${z},${t.x},${t.y}`;
      const bmp = this.cache.obtener(clave);
      const llegada = this.llegadas.get(clave);
      const alfa = !bmp ? 0 : llegada === undefined ? 1 : Math.min(1, (ahora - llegada) / FUNDIDO_MS);

      if (alfa < 1) {                              // debajo: el ancestro ampliado
        this.dibujarRelleno(t, tw, th, x0, y0, x1 - x0, y1 - y0);
      }
      if (!bmp) {
        faltantes++;
      } else {
        ctx.globalAlpha = alfa;                    // encima: el tile nuevo, apareciendo gradualmente
        ctx.drawImage(bmp, x0, y0, x1 - x0, y1 - y0);
        ctx.globalAlpha = 1;
        if (alfa < 1) fundiendo = true;
      }

      if (this.cuadricula) {
        ctx.strokeStyle = 'rgba(127, 209, 255, 0.5)';
        ctx.strokeRect(x0 + 0.5, y0 + 0.5, x1 - x0 - 1, y1 - y0 - 1);
        ctx.fillStyle = '#7fd1ff';
        ctx.fillText(clave, x0 + 4, y0 + 14);
      }
    }
    this.faltantes = faltantes;
    if (animando || fundiendo) this.solicitarDibujo();   // seguir animando en el próximo cuadro
  }

  /** Mientras llega el tile, dibuja su parte de un ancestro ya cargado, ampliada. */
  dibujarRelleno(t, tw, th, dx, dy, dw, dh) {
    const T = this.meta.tile;
    for (let k = 1; k <= this.z; k++) {
      const ax = t.x >> k, ay = t.y >> k;          // quadtree implícito: ancestro k niveles arriba
      const ancestro = this.cache.obtener(`${this.z - k},${ax},${ay}`);
      if (!ancestro) continue;
      const escala = 2 ** k;
      const sx = (t.x * T) / escala - ax * T;      // dónde cae este tile dentro del ancestro
      const sy = (t.y * T) / escala - ay * T;
      this.ctx.drawImage(ancestro, sx, sy, tw / escala, th / escala, dx, dy, dw, dh);
      return;
    }
  }

  // ---------- Animación y movimiento ----------

  /** Acerca el zoom a su objetivo, un poco en cada cuadro. Devuelve true si sigue animando. */
  avanzarAnimacion() {
    const restante = Math.log2(this.zoomObjetivo / this.zoom);
    if (Math.abs(restante) < 0.002) {
      if (this.zoom !== this.zoomObjetivo) this.aplicarZoom(this.zoomObjetivo);
      return false;
    }
    this.aplicarZoom(this.zoom * 2 ** (restante * SUAVIZADO));   // paso en escala logarítmica
    return true;
  }

  aplicarZoom(nuevo) {
    const a = this.ancla ?? { x: this.w / 2, y: this.h / 2 };
    const px = this.cx + (a.x - this.w / 2) / this.zoom;       // punto de la imagen bajo el ancla
    const py = this.cy + (a.y - this.h / 2) / this.zoom;
    this.zoom = nuevo;
    this.cx = px - (a.x - this.w / 2) / this.zoom;             // ...sigue bajo el ancla
    this.cy = py - (a.y - this.h / 2) / this.zoom;
    this.cambio();
  }

  mover(dx, dy) {
    this.cx += dx / this.zoom;
    this.cy += dy / this.zoom;
    this.cambio();
  }

  cambio() {
    if (!this.meta) return;
    this.cx = Math.min(Math.max(this.cx, 0), this.meta.ancho);  // el centro no sale de la imagen
    this.cy = Math.min(Math.max(this.cy, 0), this.meta.alto);
    this.z = this.nivelPara(this.zoom);
    this.solicitarDibujo();
    this.alCambiarVista();                                       // app.js limita a 1 VIEWPORT / 100 ms
  }

  ajustarTamanio() {
    const dpr = window.devicePixelRatio || 1;
    this.w = this.canvas.clientWidth;
    this.h = this.canvas.clientHeight;
    this.canvas.width = Math.round(this.w * dpr);
    this.canvas.height = Math.round(this.h * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.ctx.imageSmoothingQuality = 'high';
    this.ctx.font = '12px ui-monospace, Consolas, monospace';
  }

  registrarEntrada() {
    const c = this.canvas;
    let ultimo = null;

    c.addEventListener('pointerdown', e => {
      ultimo = { x: e.clientX, y: e.clientY };
      c.setPointerCapture(e.pointerId);
    });
    c.addEventListener('pointermove', e => {
      if (!ultimo) return;
      this.mover(ultimo.x - e.clientX, ultimo.y - e.clientY);
      ultimo = { x: e.clientX, y: e.clientY };
    });
    c.addEventListener('pointerup', () => { ultimo = null; });
    c.addEventListener('pointercancel', () => { ultimo = null; });

    c.addEventListener('wheel', e => {
      e.preventDefault();
      if (!this.meta) return;
      const r = c.getBoundingClientRect();
      this.ancla = { x: e.clientX - r.left, y: e.clientY - r.top };
      const delta = e.deltaMode === 1 ? e.deltaY * 33 : e.deltaY;  // algunos navegadores miden en líneas
      // Mouse (~100 por paso) o trackpad (valores chicos): el objetivo sigue al gesto
      this.zoomObjetivo = this.limitarZoom(this.zoomObjetivo * 2 ** (-delta / 250));
      this.solicitarDibujo();                                      // arranca la animación
    }, { passive: false });

    window.addEventListener('resize', () => {
      this.ajustarTamanio();
      this.cambio();
    });
    window.addEventListener('keydown', e => {
      if (e.key === 'g' || e.key === 'G') {
        this.cuadricula = !this.cuadricula;
        this.solicitarDibujo();
      }
    });
  }
}