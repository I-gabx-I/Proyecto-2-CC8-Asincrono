package tools;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Iterator;

public class Prueba2Franja {
    static final int T = 256; // tamaño de tile

    public static void main(String[] args) throws Exception {
        File archivo = new File(args[0]);

        try (ImageInputStream in = ImageIO.createImageInputStream(archivo)) {
            // 1. Abrir el lector (igual que antes)
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(in);
            ImageReader lector = lectores.next();
            lector.setInput(in, true, true);
            int w = lector.getWidth(0);
            int h = lector.getHeight(0);

            int columnas = (w + T - 1) / T;   // división hacia arriba = ⌈w/T⌉
            int filas    = (h + T - 1) / T;   // ⌈h/T⌉
            System.out.printf("Imagen %d x %d -> %d columnas x %d filas = %d tiles%n",
                    w, h, columnas, filas, columnas * filas);

            // 2. Tres franjas para comparar: arriba, centro, abajo
            int[] filasPrueba = {0, filas / 2, filas - 2};

            for (int fila : filasPrueba) {
                int y = fila * T;
                int alto = Math.min(T, h - y);   // la última franja puede ser más baja

                // 3. Leer la franja COMPLETA: todo el ancho, 256 filas de alto
                ImageReadParam param = lector.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(0, y, w, alto));

                long t0 = System.nanoTime();
                BufferedImage franja = lector.read(0, param);
                long msLectura = (System.nanoTime() - t0) / 1_000_000;

                // 4. Cortar la franja en tiles y comprimir cada uno a JPEG (en memoria)
                long t1 = System.nanoTime();
                long bytesTotales = 0;
                for (int col = 0; col < columnas; col++) {
                    int x = col * T;
                    int ancho = Math.min(T, w - x); // el último tile puede ser más angosto
                    BufferedImage tile = franja.getSubimage(x, 0, ancho, alto);

                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    ImageIO.write(tile, "jpg", jpeg);
                    bytesTotales += jpeg.size();

                    // Guardar solo el tile del medio, para revisarlo con los ojos
                    if (col == columnas / 2) {
                        ImageIO.write(tile, "jpg", new File("franja_fila" + fila + ".jpg"));
                    }
                }
                long msCorte = (System.nanoTime() - t1) / 1_000_000;

                // 5. Resultados de esta franja
                Runtime rt = Runtime.getRuntime();
                long usadaMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                long promedioKB = bytesTotales / columnas / 1024;
                System.out.printf("Fila %3d | lectura %5d ms | corte+JPEG %5d ms | %d tiles | promedio %d KB/tile | memoria %d MB%n",
                        fila, msLectura, msCorte, columnas, promedioKB, usadaMB);

                franja = null; // soltar la franja antes de leer la siguiente
            }

            lector.dispose();
        }
    }
}