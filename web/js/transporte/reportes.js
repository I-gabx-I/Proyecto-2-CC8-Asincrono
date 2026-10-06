/**
 * Mediciones del receptor para REPORT (PROTOCOLO.md §12.2), al estilo de los
 * receiver reports de RTCP (RFC 3550 §6.4). No confirma tiles: solo mide.
 */
export class Reportero {
  constructor() {
    this.reiniciar();
  }

  reiniciar() {
    this.cola = 0;              // tiles recibidos que todavía no terminan de decodificarse
    this.dec = 0;               // promedio móvil del tiempo de decodificación (ms)
    this.jit = 0;               // variación entre llegadas (ms)
    this.ultimaLlegada = null;
    this.ultimoDelta = null;
  }

  /** Cada mensaje binario recibido. Jitter de RTCP: J ← J + (|Δₖ − Δₖ₋₁| − J) / 16. */
  llegada(ahora) {
    if (this.ultimaLlegada !== null) {
      const delta = ahora - this.ultimaLlegada;
      if (this.ultimoDelta !== null) this.jit += (Math.abs(delta - this.ultimoDelta) - this.jit) / 16;
      this.ultimoDelta = delta;
    }
    this.ultimaLlegada = ahora;
  }

  inicioDecodificacion() {
    this.cola++;
  }

  /** Promedio móvil con peso 1/8, como el RTT suavizado de TCP. */
  finDecodificacion(ms) {
    this.cola = Math.max(0, this.cola - 1);
    this.dec += (ms - this.dec) / 8;
  }

  mensaje(max, perdidos, recuperados) {
    const acotar = (v, m) => Math.min(m, Math.max(0, Math.round(v)));
    return `REPORT|MAX:${max}|PERD:${perdidos}|COLA:${acotar(this.cola, 10000)}` +
           `|DEC:${acotar(this.dec, 60000)}|JIT:${acotar(this.jit, 60000)}|REC:${recuperados}`;
  }
}