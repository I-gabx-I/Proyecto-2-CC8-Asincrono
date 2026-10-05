package pimg.ingest;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Lector PNG propio, en streaming: recorre el archivo UNA sola vez, de principio a fin.
 * El lector del JDK descomprime desde el inicio en cada lectura por región (costo cuadrático).
 *
 * Paso 1: firma, IHDR y posicionarse en el primer IDAT.
 * Paso 2: unir los IDAT en un solo flujo y descomprimirlo.
 */
public final class PngSource implements ImageSource {
    static final byte[] FIRMA = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int BUFFER = 1 << 20;   // leer el disco de a 1 MB

    private final DataInputStream archivo;       // DataInputStream: readInt() lee big-endian, como PNG
    private final int ancho;
    private final int alto;
    private final int canales;                   // bytes por píxel: 1 gris, 3 RGB, 4 RGBA
    private final long largoPrimerIdat;

    private final Inflater inflater = new Inflater();   // PASO 2: descompresor zlib del JDK
    private final InputStream pixeles;                   // PASO 2: IDAT unidos y ya descomprimidos
    private byte[] filaPrevia;                   // PASO 3: fila anterior ya decodificada (la necesitan Up, Average y Paeth)
    private byte[] filaActual;                   // PASO 3: fila que se está decodificando
    private int siguienteFila = 0;               // PASO 3: las franjas se entregan en orden
    private long nanosLectura = 0;               // PASO 4: diagnóstico
    private long nanosConversion = 0;

    public PngSource(Path ruta) throws IOException {
        if (!Files.isRegularFile(ruta)) {
            throw new IOException("No existe el archivo: " + ruta.toAbsolutePath());
        }
        archivo = new DataInputStream(new BufferedInputStream(Files.newInputStream(ruta), BUFFER));
        try {
            // 1. Firma
            if (!Arrays.equals(archivo.readNBytes(8), FIRMA)) {
                throw new IOException("No es un PNG: firma incorrecta");
            }

            // 2. IHDR: siempre el primer chunk, 13 bytes
            int largo = archivo.readInt();
            if (!leerTipo().equals("IHDR") || largo != 13) {
                throw new IOException("PNG invalido: falta IHDR");
            }
            long w = Integer.toUnsignedLong(archivo.readInt());   // los enteros de PNG no tienen signo
            long h = Integer.toUnsignedLong(archivo.readInt());
            int bits = archivo.readUnsignedByte();
            int color = archivo.readUnsignedByte();
            int compresion = archivo.readUnsignedByte();
            int filtro = archivo.readUnsignedByte();
            int entrelazado = archivo.readUnsignedByte();
            archivo.skipNBytes(4);                                // CRC del IHDR

            // 3. Validar que es un PNG que sabemos leer por franjas
            if (w <= 0 || h <= 0 || w > Integer.MAX_VALUE / 4 || h > Integer.MAX_VALUE) {
                throw new IOException("Dimensiones no soportadas: " + w + " x " + h);
            }
            if (bits != 8) {
                throw new IOException("Solo se soportan 8 bits por canal (tiene " + bits + ")");
            }
            if (compresion != 0 || filtro != 0) {
                throw new IOException("Metodo de compresion o filtro desconocido");
            }
            if (entrelazado != 0) {
                throw new IOException("PNG entrelazado (Adam7): no se puede leer por franjas");
            }
            canales = switch (color) {
                case 0 -> 1;   // gris
                case 2 -> 3;   // RGB  <- las imágenes de evaluación
                case 6 -> 4;   // RGBA
                default -> throw new IOException("Tipo de color no soportado: " + color);
            };
            ancho = (int) w;
            alto = (int) h;

            // 4. Saltar chunks (eXIf, pHYs, ...) hasta quedar parado en el primer IDAT
            while (true) {
                long l = Integer.toUnsignedLong(archivo.readInt());
                String tipo = leerTipo();
                if (tipo.equals("IDAT")) {
                    largoPrimerIdat = l;
                    break;
                }
                if (tipo.equals("IEND")) {
                    throw new IOException("PNG sin datos de imagen (IDAT)");
                }
                archivo.skipNBytes(l + 4);                        // datos + CRC
            }

            // 5. PASO 2: flujo de píxeles = IDAT unidos -> descompresor
            pixeles = new InflaterInputStream(new FlujoIdat(largoPrimerIdat), inflater, 1 << 16);
                        // 6. PASO 3: dos filas en memoria. La "fila -1" es todo ceros (especificación PNG §9.2)
            filaPrevia = new byte[ancho * canales];
            filaActual = new byte[ancho * canales];
        } catch (IOException | RuntimeException e) {
            archivo.close();                                      // no dejar el archivo abierto si falla
            throw e;
        }
    }

    @Override public int ancho() { return ancho; }
    @Override public int alto()  { return alto; }
    public int canales()         { return canales; }
    public long largoPrimerIdat() { return largoPrimerIdat; }
    @Override public String descripcion()        { return "PNG propio en streaming (" + canales + " canales, 8 bits)"; }
    @Override public double segundosLectura()    { return nanosLectura / 1e9; }
    @Override public double segundosConversion() { return nanosConversion / 1e9; }

    /** Bytes que DEBE producir la descompresión: cada fila = 1 byte de filtro + ancho × canales. */
    public long bytesEsperados() {
        return (long) alto * (1 + (long) ancho * canales);
    }

    /**
     * PASO 2 (diagnóstico): descomprime todo el flujo sin interpretarlo y devuelve cuántos bytes salieron.
     * Solo sirve para probar el paso; consume el archivo completo.
     */
    public long descomprimirTodo() throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = pixeles.read(buf)) > 0) {
            total += n;
        }
        return total;
    }

        /**
     * PASO 3: entrega las filas [y, y + filas) en el formato interno BGR.
     * Solo en orden: un PNG es un flujo comprimido y no se puede retroceder.
     */
    @Override
    public Franja leerFranja(int y, int filas) throws IOException {
        if (y != siguienteFila) {
            throw new IllegalStateException("PngSource lee en orden: se esperaba y=" + siguienteFila + " y llego y=" + y);
        }
        if (filas <= 0 || y + filas > alto) {
            throw new IllegalArgumentException("Franja fuera de la imagen: y=" + y + " filas=" + filas);
        }
        byte[] bgr = new byte[ancho * filas * 3];
        for (int f = 0; f < filas; f++) {
                        long t0 = System.nanoTime();
            leerFila();                                   // deja la fila decodificada en filaActual
            long t1 = System.nanoTime();
            aBgr(filaActual, bgr, f * ancho * 3);
            nanosConversion += System.nanoTime() - t1;
            nanosLectura += t1 - t0;

            byte[] tmp = filaPrevia;                      // la actual pasa a ser la anterior de la siguiente
            filaPrevia = filaActual;
            filaActual = tmp;
        }
        siguienteFila += filas;
        return new Franja(ancho, filas, bgr);
    }

    /** Lee una fila del flujo descomprimido (1 byte de filtro + datos) y le quita el filtro. */
    private void leerFila() throws IOException {
        int tipoFiltro = pixeles.read();
        if (tipoFiltro < 0
                || pixeles.readNBytes(filaActual, 0, filaActual.length) != filaActual.length) {
            throw new EOFException("PNG truncado en la fila " + siguienteFila);
        }
        quitarFiltro(tipoFiltro, filaActual, filaPrevia, canales);
    }

    /**
     * Los 5 filtros de PNG (§9.2). Cada byte se guardó como diferencia respecto a una predicción;
     * decodificar es sumarle de vuelta esa predicción. El "+=" sobre un byte ya es módulo 256.
     *   a = mismo canal a la izquierda (fila[i - bpp]), b = arriba (previa[i]), c = arriba-izquierda (previa[i - bpp])
     */
    static void quitarFiltro(int tipo, byte[] fila, byte[] previa, int bpp) throws IOException {
        int n = fila.length;
        switch (tipo) {
            case 0 -> { }                                                     // None
            case 1 -> {                                                       // Sub
                for (int i = bpp; i < n; i++) fila[i] += fila[i - bpp];
            }
            case 2 -> {                                                       // Up
                for (int i = 0; i < n; i++) fila[i] += previa[i];
            }
            case 3 -> {                                                       // Average
                for (int i = 0; i < bpp; i++) fila[i] += (byte) ((previa[i] & 0xFF) >>> 1);   // a = 0
                for (int i = bpp; i < n; i++) {
                    fila[i] += (byte) (((fila[i - bpp] & 0xFF) + (previa[i] & 0xFF)) >>> 1);
                }
            }
            case 4 -> {                                                       // Paeth
                for (int i = 0; i < bpp; i++) fila[i] += previa[i];             // a = c = 0 -> Paeth = b
                for (int i = bpp; i < n; i++) {
                    fila[i] += (byte) paeth(fila[i - bpp] & 0xFF, previa[i] & 0xFF, previa[i - bpp] & 0xFF);
                }
            }
            default -> throw new IOException("Filtro PNG invalido: " + tipo);
        }
    }

    /** Predictor de Paeth: de a, b y c, el más cercano a a + b - c (en empate: a, luego b). */
    static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    /** Copia una fila decodificada al formato interno BGR (el que usa toda la ingesta). */
    private void aBgr(byte[] src, byte[] destino, int offset) {
        int d = offset;
        switch (canales) {
            case 3 -> {                                    // R G B -> B G R
                for (int s = 0; s < src.length; s += 3) {
                    destino[d++] = src[s + 2];
                    destino[d++] = src[s + 1];
                    destino[d++] = src[s];
                }
            }
            case 4 -> {                                    // R G B A -> B G R (se descarta el alfa)
                for (int s = 0; s < src.length; s += 4) {
                    destino[d++] = src[s + 2];
                    destino[d++] = src[s + 1];
                    destino[d++] = src[s];
                }
            }
            default -> {                                   // gris -> B = G = R
                for (byte g : src) {
                    destino[d++] = g;
                    destino[d++] = g;
                    destino[d++] = g;
                }
            }
        }
    }

    private String leerTipo() throws IOException {
        return new String(archivo.readNBytes(4), StandardCharsets.US_ASCII);
    }

    @Override
    public void close() throws IOException {
        inflater.end();                                           // libera la memoria nativa de zlib
        archivo.close();
    }

    /**
     * PASO 2: presenta los datos de TODOS los chunks IDAT como un único flujo continuo.
     * Entre un IDAT y el siguiente salta el CRC (4 B), la longitud (4 B) y el tipo (4 B).
     * Termina al llegar a un chunk que no es IDAT (normalmente IEND).
     */
    private final class FlujoIdat extends InputStream {
        private long restante;          // bytes que faltan del IDAT actual
        private boolean fin = false;

        FlujoIdat(long largoPrimerIdat) {
            this.restante = largoPrimerIdat;
        }

        /** Si el IDAT actual se agotó, avanza al siguiente. Devuelve false si ya no hay más IDAT. */
        private boolean hayDatos() throws IOException {
            while (restante == 0 && !fin) {               // while: un IDAT puede medir 0 bytes
                archivo.skipNBytes(4);                     // CRC del IDAT que terminó
                long largo = Integer.toUnsignedLong(archivo.readInt());
                if (leerTipo().equals("IDAT")) {
                    restante = largo;
                } else {
                    fin = true;                            // IEND u otro chunk: se acabaron los datos
                }
            }
            return !fin;
        }

        @Override
        public int read() throws IOException {
            if (!hayDatos()) return -1;
            restante--;
            return archivo.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!hayDatos()) return -1;
            int n = archivo.read(b, off, (int) Math.min(len, restante));   // nunca leer más allá del IDAT
            if (n < 0) throw new EOFException("PNG truncado dentro de un IDAT");
            restante -= n;
            return n;
        }
    }
}