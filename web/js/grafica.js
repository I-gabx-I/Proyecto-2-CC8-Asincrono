/** Gráfica de la tasa R y la ocupación Q de los últimos segundos (PROTOCOLO.md §12.7). */
export class GraficaControl {
  constructor(canvas, segundos = 30) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.ventanaMs = segundos * 1000;
    this.puntos = [];           // { t, r, q }
  }

  agregar(r, q) {
    const t = performance.now();
    this.puntos.push({ t, r, q });
    while (this.puntos.length && t - this.puntos[0].t > this.ventanaMs) this.puntos.shift();
  }

  /** Dibuja Q (naranja), R (azul) y la línea punteada de Q*. Devuelve las escalas usadas. */
  dibujar(qObjetivo) {
    const { canvas, ctx, puntos } = this;
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    const maxQ = Math.max(qObjetivo * 2, ...puntos.map(p => p.q));
    const maxR = Math.max(10, ...puntos.map(p => p.r));
    const ahora = performance.now();
    const x = t => w - (ahora - t) / this.ventanaMs * w;
    const y = (v, max) => h - 1 - v / max * (h - 2);

    ctx.strokeStyle = '#8a93a3';
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(0, y(qObjetivo, maxQ));
    ctx.lineTo(w, y(qObjetivo, maxQ));
    ctx.stroke();
    ctx.setLineDash([]);

    const linea = (color, valor, max) => {
      ctx.strokeStyle = color;
      ctx.beginPath();
      puntos.forEach((p, i) => (i ? ctx.lineTo : ctx.moveTo).call(ctx, x(p.t), y(valor(p), max)));
      ctx.stroke();
    };
    linea('#7fd1ff', p => p.r, maxR);
    linea('#ffb86b', p => p.q, maxQ);
    return { maxQ, maxR };
  }
}