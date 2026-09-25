package pimg.ingest;

/**
 * Bloque de filas completas de un nivel, en BGR intercalado (3 bytes por píxel).
 * El píxel (x, y) empieza en el índice (y * ancho + x) * 3.
 */
public record Franja(int ancho, int alto, byte[] bgr) {
    public Franja {
        if ((long) ancho * alto * 3 != bgr.length) {
            throw new IllegalArgumentException("Tamano de datos inconsistente");
        }
    }
}