/**
 * Filtro de Bloom con el contrato exacto de PROTOCOLO.md §13.3 (idéntico a pimg.transporte.FiltroBloom).
 * m = 4096 bits, k = 7; clave = SEM ‖ Z ‖ X ‖ Y (13 bytes big-endian); FNV-1a + fmix32, doble hashing.
 * Math.imul y >>> 0 reproducen la aritmética de 32 bits sin signo de Java.
 */
export const M = 4096;
export const K = 7;

const octeto = (h, b) => Math.imul(h ^ (b & 0xFF), 0x01000193) >>> 0;

function entero(h, v) {                       // 4 bytes big-endian
  for (let s = 24; s >= 0; s -= 8) h = octeto(h, v >>> s);
  return h;
}

export function fmix32(h) {
  h ^= h >>> 16;
  h = Math.imul(h, 0x85EBCA6B);
  h ^= h >>> 13;
  h = Math.imul(h, 0xC2B2AE35);
  h ^= h >>> 16;
  return h >>> 0;
}

export function h1(sem, z, x, y) {
  let h = 0x811C9DC5;
  h = entero(h, sem);
  h = octeto(h, z);
  h = entero(h, x);
  return entero(h, y);
}

export function posiciones(sem, z, x, y) {
  const a = h1(sem, z, x, y);
  const b = (fmix32(a) | 1) >>> 0;
  const p = [];
  for (let i = 0; i < K; i++) p.push(((a + Math.imul(i, b)) >>> 0) % M);
  return p;
}

export class FiltroBloom {
  constructor(semilla) {
    this.semilla = semilla >>> 0;
    this.bits = new Uint8Array(M / 8);
  }

  agregar(z, x, y) {
    for (const p of posiciones(this.semilla, z, x, y)) this.bits[p >>> 3] |= 1 << (p & 7);
  }

  contiene(z, x, y) {
    return posiciones(this.semilla, z, x, y).every(p => (this.bits[p >>> 3] & (1 << (p & 7))) !== 0);
  }

  /** BITS del mensaje BLOOM o RESUME: Base64 de los 512 bytes (684 caracteres). */
  base64() {
    return btoa(String.fromCharCode(...this.bits));
  }
}