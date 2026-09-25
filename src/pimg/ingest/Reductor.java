package pimg.ingest;

/** Reduce una franja a la mitad promediando bloques de 2x2 píxeles (filtro de caja). */
public final class Reductor {
    private Reductor() {}

    public static Franja reducir(Franja f) {
        int w = f.ancho();
        int h = f.alto();
        int w2 = (w + 1) / 2;   // ⌈w/2⌉: coincide con PyramidLayout
        int h2 = (h + 1) / 2;
        byte[] src = f.bgr();
        byte[] dst = new byte[w2 * h2 * 3];

        int i = 0;
        for (int y = 0; y < h2; y++) {
            int y0 = 2 * y;
            int y1 = Math.min(y0 + 1, h - 1);            // borde inferior impar
            int fila0 = y0 * w * 3;
            int fila1 = y1 * w * 3;
            for (int x = 0; x < w2; x++) {
                int x0 = 2 * x * 3;
                int x1 = Math.min(2 * x + 1, w - 1) * 3; // borde derecho impar
                for (int c = 0; c < 3; c++) {
                    int suma = (src[fila0 + x0 + c] & 0xFF) + (src[fila0 + x1 + c] & 0xFF)
                             + (src[fila1 + x0 + c] & 0xFF) + (src[fila1 + x1 + c] & 0xFF);
                    dst[i++] = (byte) ((suma + 2) >> 2);  // promedio redondeado
                }
            }
        }
        return new Franja(w2, h2, dst);
    }
}