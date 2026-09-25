package tools;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

public class Prueba1Region {
    public static void main(String[] args) throws Exception {
        File archivo = new File(args[0]);

        try (ImageInputStream in = ImageIO.createImageInputStream(archivo)) {
            // 1. Buscar un lector que entienda el formato (TIFF)
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(in);
            if (!lectores.hasNext()) {
                System.out.println("Java no tiene lector para este formato");
                return;
            }
            ImageReader lector = lectores.next();

            // 2. Conectar el lector al archivo (solo lee la cabecera, no los píxeles)
            lector.setInput(in, true, true);
            int w = lector.getWidth(0);
            int h = lector.getHeight(0);
            System.out.printf("Lector: %s%n", lector.getClass().getSimpleName());
            System.out.printf("Dimensiones: %d x %d%n", w, h);

            // ===== NUEVO: desde aquí cambia =====

            // 3. Lista de regiones a leer: arriba, centro, abajo, y otra vez arriba
            int[][] posiciones = {
                {0, 0},                    // esquina superior izquierda
                {w / 2, h / 2},            // centro
                {w - 256, h - 256},        // esquina inferior derecha
                {0, 0}                     // repetir la primera (Java ya "caliente")
            };
            String[] nombres = {"arriba", "centro", "abajo", "arriba_otra_vez"};

            // 4. Leer cada región, medir tiempo y memoria
            for (int i = 0; i < posiciones.length; i++) {
                Rectangle region = new Rectangle(posiciones[i][0], posiciones[i][1], 256, 256);
                ImageReadParam param = lector.getDefaultReadParam();
                param.setSourceRegion(region);

                long t0 = System.nanoTime();
                BufferedImage tile = lector.read(0, param);
                long ms = (System.nanoTime() - t0) / 1_000_000;

                // 5. Guardar cada pedazo con su nombre
                ImageIO.write(tile, "png", new File("tile_" + nombres[i] + ".png"));

                // 6. Memoria usada después de esta lectura
                Runtime rt = Runtime.getRuntime();
                long usadaMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                System.out.printf("%-16s (%5d,%5d) -> %4d ms | memoria %d MB%n",
                        nombres[i], posiciones[i][0], posiciones[i][1], ms, usadaMB);
            }

            // ===== NUEVO: hasta aquí =====

            lector.dispose();
        }
    }
}