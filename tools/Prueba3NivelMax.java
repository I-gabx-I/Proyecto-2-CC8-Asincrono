package tools;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.Iterator;

public class Prueba3NivelMax {
    static final int T = 256;

    public static void main(String[] args) throws Exception {
        File archivo = new File(args[0]);     // la imagen
        Path salida = Path.of(args[1]);       // carpeta de tiles, FUERA de OneDrive

        try (ImageInputStream in = ImageIO.createImageInputStream(archivo)) {
            // 1. Abrir el lector
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(in);
            ImageReader lector = lectores.next();
            lector.setInput(in, true, true);
            int w = lector.getWidth(0);
            int h = lector.getHeight(0);

            // 2. Calcular la pirámide
            int columnas = (w + T - 1) / T;
            int filas    = (h + T - 1) / T;
            int niveles  = 1 + (int) Math.ceil(Math.log((double) Math.max(w, h) / T) / Math.log(2));
            int zMax     = niveles - 1;
            System.out.printf("Imagen %d x %d | niveles %d | nivel max z=%d: %d x %d = %d tiles%n",
                    w, h, niveles, zMax, columnas, filas, columnas * filas);

            // 3. Crear la carpeta del nivel: salida/8/
            Path carpetaNivel = salida.resolve(String.valueOf(zMax));
            Files.createDirectories(carpetaNivel);

            // 4. Recorrer TODAS las franjas
            long inicio = System.nanoTime();
            long bytesTotales = 0;
            long memoriaMaxMB = 0;
            Runtime rt = Runtime.getRuntime();

            for (int fila = 0; fila < filas; fila++) {
                int y = fila * T;
                int alto = Math.min(T, h - y);

                // 4a. Leer la franja completa
                ImageReadParam param = lector.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(0, y, w, alto));
                BufferedImage franja = lector.read(0, param);

                // 4b. Cortarla y guardar cada tile como x_y.jpg
                for (int col = 0; col < columnas; col++) {
                    int x = col * T;
                    int ancho = Math.min(T, w - x);
                    BufferedImage tile = franja.getSubimage(x, 0, ancho, alto);

                    File destino = carpetaNivel.resolve(col + "_" + fila + ".jpg").toFile();
                    ImageIO.write(tile, "jpg", destino);
                    bytesTotales += destino.length();
                }
                franja = null;

                // 4c. Registrar la memoria más alta vista
                long usadaMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                memoriaMaxMB = Math.max(memoriaMaxMB, usadaMB);

                // 4d. Mostrar progreso cada 10 franjas
                if (fila % 10 == 0 || fila == filas - 1) {
                    long seg = (System.nanoTime() - inicio) / 1_000_000_000;
                    int porcentaje = (fila + 1) * 100 / filas;
                    System.out.printf("Franja %3d/%d (%3d%%) | %4d s | %d MB en disco%n",
                            fila + 1, filas, porcentaje, seg, bytesTotales / (1024 * 1024));
                }
            }

            // 5. Resumen
            long segTotal = (System.nanoTime() - inicio) / 1_000_000_000;
            int totalTiles = columnas * filas;
            System.out.println("----- RESUMEN -----");
            System.out.printf("Tiles generados: %d%n", totalTiles);
            System.out.printf("Tiempo total: %d s (%.1f ms por tile)%n", segTotal, segTotal * 1000.0 / totalTiles);
            System.out.printf("Espacio en disco: %d MB (promedio %d KB por tile)%n",
                    bytesTotales / (1024 * 1024), bytesTotales / totalTiles / 1024);
            System.out.printf("Memoria maxima observada: %d MB (limite %d MB)%n",
                    memoriaMaxMB, rt.maxMemory() / (1024 * 1024));

            lector.dispose();
        }
    }
}