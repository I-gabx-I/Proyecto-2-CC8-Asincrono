package pimg.tiles;

/** Matemática de la pirámide (PROTOCOLO.md §4.1). No lee ni escribe nada. */
public final class PyramidLayout {
    private final int ancho;
    private final int alto;
    private final int tile;
    private final int niveles;

    public PyramidLayout(int ancho, int alto, int tile) {
        if (ancho <= 0 || alto <= 0 || tile <= 0) {
            throw new IllegalArgumentException("Dimensiones invalidas");
        }
        this.ancho = ancho;
        this.alto = alto;
        this.tile = tile;

        // Dividir a la mitad (hacia arriba) hasta que la imagen quepa en un tile
        int n = 1;
        int lado = Math.max(ancho, alto);
        while (lado > tile) {
            lado = (lado + 1) / 2;
            n++;
        }
        this.niveles = n;
    }

    public int niveles()       { return niveles; }
    public int zMax()          { return niveles - 1; }
    public int tile()          { return tile; }
    public int anchoOriginal() { return ancho; }
    public int altoOriginal()  { return alto; }

    /** Ancho en píxeles del nivel z: ⌈W / 2^(zMax - z)⌉ */
    public int ancho(int z) { return (int) Math.ceilDiv((long) ancho, 1L << escala(z)); }
    public int alto(int z)  { return (int) Math.ceilDiv((long) alto, 1L << escala(z)); }

    public int columnas(int z) { return Math.ceilDiv(ancho(z), tile); }
    public int filas(int z)    { return Math.ceilDiv(alto(z), tile); }
    public long tiles(int z)   { return (long) columnas(z) * filas(z); }

    public long tilesTotales() {
        long total = 0;
        for (int z = 0; z < niveles; z++) {
            total += tiles(z);
        }
        return total;
    }

    /** ¿Existe el tile (z, x, y)? El servidor lo usará para responder 416 OUT_OF_RANGE. */
    public boolean existe(int z, int x, int y) {
        return z >= 0 && z < niveles
            && x >= 0 && x < columnas(z)
            && y >= 0 && y < filas(z);
    }

    private int escala(int z) {
        if (z < 0 || z >= niveles) {
            throw new IllegalArgumentException("Nivel fuera de rango: " + z);
        }
        return zMax() - z;
    }
}