import { crc32 } from '../crc32.js';

/**
 * Recuperación FEC del cliente (PROTOCOLO.md §11.5).
 * Conserva los datos crudos de los últimos tiles recibidos; con una PARIDAD reconstruye
 * el único miembro que falte de su grupo: paridad ⊕ los demás, recortado y verificado con su CRC.
 */
export class ReceptorFec {
  constructor(capacidad = 32) {
    this.capacidad = capacidad;
    this.recientes = new Map();      // "z,x,y" -> Uint8Array, en orden de llegada
  }

  guardar(clave, datos) {
    this.recientes.delete(clave);
    this.recientes.set(clave, datos);
    if (this.recientes.size > this.capacidad) {
      this.recientes.delete(this.recientes.keys().next().value);   // el más antiguo
    }
  }

  vaciar() {
    this.recientes.clear();
  }

  /**
   * miembros: [{ clave, z, x, y, fmt, largo, crc }]; paridad: Uint8Array de LONG_P bytes.
   * Devuelve { estado: 'innecesaria' | 'irrecuperable' | 'crc' | 'recuperado', tile?, datos? }.
   */
  procesar(miembros, paridad) {
    const faltan = miembros.filter(m => !this.recientes.has(m.clave));
    if (faltan.length === 0) return { estado: 'innecesaria' };
    if (faltan.length > 1) return { estado: 'irrecuperable' };   // XOR recupera solo uno por grupo

    const falta = faltan[0];
    const c = paridad.slice();                 // copia: no se modifica el mensaje recibido
    for (const m of miembros) {
      if (m === falta) continue;
      const d = this.recientes.get(m.clave);
      for (let i = 0; i < d.length; i++) c[i] ^= d[i];
    }
    const datos = c.subarray(0, falta.largo);  // quita el relleno de ceros
    if (crc32(datos) !== falta.crc) return { estado: 'crc' };
    return { estado: 'recuperado', tile: falta, datos };
  }
}