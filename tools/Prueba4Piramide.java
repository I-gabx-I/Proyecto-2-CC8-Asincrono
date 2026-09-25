package tools;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;

public class Prueba4Piramide {
    static final int T = 256;

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);          // carpeta "tiles"
        int w = Integer.parseInt(args[1]);     // ancho original: 40000
        int h = Integer.parseInt(args[2]);     // alto original: 30131

        // 1. Datos del nivel máximo (el que ya existe)
        int niveles   = 1 + (int) Math.ceil(Math.log((double) Math.max(w, h) / T) / Math.log(2));
        int zMax      = niveles - 1;
        int colsHijo  = (w + T - 1) / T;
        int filasHijo = (h + T - 1) / T;

        Runtime rt = Runtime.getRuntime();
        long memoriaMaxMB = 0;
        long bytesTotales = 0;
        int tilesTotales = 0;
        long inicioTotal = System.nanoTime();

        // 2. Construir cada nivel, de zMax-1 hacia 0
        for (int z = zMax - 1; z >= 0; z--) {
            int cols  = (colsHijo + 1) / 2;    // ⌈colsHijo / 2⌉
            int filas = (filasHijo + 1) / 2;   // ⌈filasHijo / 2⌉
            Path dirHijo  = base.resolve(String.valueOf(z + 1));
            Path dirPadre = base.resolve(String.valueOf(z));
            Files.createDirectories(dirPadre);

            long t0 = System.nanoTime();
            long bytesNivel = 0;

            for (int y = 0; y < filas; y++) {
                for (int x = 0; x < cols; x++) {

                    // 2a. Leer los hasta 4 hijos. hijos[dy][dx]; null si no existe (borde)
                    BufferedImage[][] hijos = new BufferedImage[2][2];
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int hx = 2 * x + dx;
                            int hy = 2 * y + dy;
                            if (hx < colsHijo && hy < filasHijo) {
                                File f = dirHijo.resolve(hx + "_" + hy + ".jpg").toFile();
                                hijos[dy][dx] = ImageIO.read(f);
                            }
                        }
                    }

                    // 2b. Tamaño del lienzo según los hijos que existen
                    int ancho = hijos[0][0].getWidth()
                              + (hijos[0][1] != null ? hijos[0][1].getWidth() : 0);
                    int alto  = hijos[0][0].getHeight()
                              + (hijos[1][0] != null ? hijos[1][0].getHeight() : 0);

                    // 2c. Pegar los hijos en el lienzo
                    BufferedImage lienzo = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = lienzo.createGraphics();
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            if (hijos[dy][dx] != null) {
                                g.drawImage(hijos[dy][dx], dx * T, dy * T, null);
                            }
                        }
                    }
                    g.dispose();

                    // 2d. Reducir a la mitad (redondeando hacia arriba)
                    int anchoP = (ancho + 1) / 2;
                    int altoP  = (alto + 1) / 2;
                    BufferedImage padre = new BufferedImage(anchoP, altoP, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = padre.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(lienzo, 0, 0, anchoP, altoP, null);
                    g2.dispose();

                    // 2e. Guardar el tile padre
                    File destino = dirPadre.resolve(x + "_" + y + ".jpg").toFile();
                    ImageIO.write(padre, "jpg", destino);
                    bytesNivel += destino.length();
                }

                long usadaMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                memoriaMaxMB = Math.max(memoriaMaxMB, usadaMB);
            }

            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("Nivel %d | %3d x %3d = %5d tiles | %6d ms | %4d KB en disco%n",
                    z, cols, filas, cols * filas, ms, bytesNivel / 1024);

            bytesTotales += bytesNivel;
            tilesTotales += cols * filas;

            // El nivel recién creado es el "hijo" del siguiente
            colsHijo = cols;
            filasHijo = filas;
        }

        // 3. Verificar el nivel 0
        BufferedImage raiz = ImageIO.read(base.resolve("0").resolve("0_0.jpg").toFile());
        long segTotal = (System.nanoTime() - inicioTotal) / 1_000_000_000;

        System.out.println("----- RESUMEN -----");
        System.out.printf("Tiles generados (niveles 0 a %d): %d%n", zMax - 1, tilesTotales);
        System.out.printf("Tiempo total: %d s%n", segTotal);
        System.out.printf("Espacio en disco: %d MB%n", bytesTotales / (1024 * 1024));
        System.out.printf("Memoria maxima observada: %d MB (limite %d MB)%n",
                memoriaMaxMB, rt.maxMemory() / (1024 * 1024));
        System.out.printf("Nivel 0: %d x %d (esperado 157 x 118)%n", raiz.getWidth(), raiz.getHeight());
    }
}   