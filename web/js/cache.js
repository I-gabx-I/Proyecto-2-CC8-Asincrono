/** Caché LRU de tiles decodificados (ImageBitmap), limitada en cantidad. */
export class CacheTiles {
  constructor(maximo, alExpulsar) {
    this.maximo = maximo;
    this.alExpulsar = alExpulsar;   // avisa qué clave salió (para enviar EVICT)
    this.mapa = new Map();          // conserva el orden: el primero es el menos usado
  }

  obtener(clave) {
    const bmp = this.mapa.get(clave);
    if (bmp) {                      // re-insertar = marcar como usado recientemente
      this.mapa.delete(clave);
      this.mapa.set(clave, bmp);
    }
    return bmp;
  }

  poner(clave, bmp) {
    const previo = this.mapa.get(clave);
    if (previo) {
      previo.close();
      this.mapa.delete(clave);
    }
    this.mapa.set(clave, bmp);
    while (this.mapa.size > this.maximo) {
      const [claveVieja, bmpViejo] = this.mapa.entries().next().value;
      this.mapa.delete(claveVieja);
      bmpViejo.close();             // libera la memoria del bitmap YA, sin esperar al GC
      this.alExpulsar(claveVieja);
    }
  }

  vaciar() {
    for (const bmp of this.mapa.values()) bmp.close();
    this.mapa.clear();
  }

  get tamanio() { return this.mapa.size; }

  bytesEstimados() {
    let total = 0;
    for (const bmp of this.mapa.values()) total += bmp.width * bmp.height * 4; // RGBA
    return total;
  }
}