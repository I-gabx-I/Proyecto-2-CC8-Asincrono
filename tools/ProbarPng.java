import pimg.ingest.Franja;
import pimg.ingest.PngSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Pruebas de la Fase 1.
 *   ProbarPng <png>...               paso 1: cabecera
 *   ProbarPng --contar <png>...      paso 2: descomprime todo y compara con lo esperado
 *   ProbarPng --verificar <png>...   paso 3: compara contra el lector del JDK, píxel por píxel
 */
public class ProbarPng {
    public static void main(String[] args) throws Exception {
        String modo = args.length > 0 && args[0].startsWith("--") ? args[0] : "";
        for (int i = modo.isEmpty() ? 0 : 1; i < args.length; i++) {
            Path p = Path.of(args[i]);
            try (PngSource png = new PngSource(p)) {
                if (modo.equals("--verificar")) {
                    verificar(p, png);
                } else if (modo.equals("--contar")) {
                    contar(p, png);
                } else {
                    System.out.printf("%-28s %6d x %-6d | canales %d | primer IDAT %d bytes%n",
                            p.getFileName(), png.ancho(), png.alto(), png.canales(), png.largoPrimerIdat());
                }
            } catch (Exception e) {
                System.out.printf("%-28s ERROR: %s%n", p.getFileName(), e);
            }
        }
    }

    /** Paso 2: los bytes descomprimidos deben ser exactamente alto × (1 + ancho × canales). */
    private static void contar(Path p, PngSource png) throws Exception {
        long t0 = System.nanoTime();
        long obtenidos = png.descomprimirTodo();
        double seg = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%-28s esperados %,15d | obtenidos %,15d | %s | %6.1f s | %4.0f MB/s%n",
                p.getFileName(), png.bytesEsperados(), obtenidos,
                obtenidos == png.bytesEsperados() ? "OK" : "DIFERENTE",
                seg, obtenidos / 1048576.0 / Math.max(seg, 1e-9));
    }

    /**
     * Paso 3: compara PngSource contra el lector del JDK, píxel por píxel.
     * Hasta 40 megapíxeles: ImageIO decodifica la imagen completa y se compara todo.
     * Más grande: ImageIO no cabe en memoria; solo se recorre con PngSource.
     */
    private static void verificar(Path p, PngSource png) throws Exception {
        int w = png.ancho(), h = png.alto();
        BufferedImage ref = (long) w * h <= 40_000_000L ? ImageIO.read(p.toFile()) : null;

        long distintos = 0;
        long t0 = System.nanoTime();
        for (int y = 0; y < h; y += 256) {
            Franja f = png.leerFranja(y, Math.min(256, h - y));
            if (ref == null) continue;
            byte[] bgr = f.bgr();
            for (int fy = 0; fy < f.alto(); fy++) {
                for (int x = 0; x < w; x++) {
                    int rgb = ref.getRGB(x, y + fy);
                    int k = (fy * w + x) * 3;
                    if ((bgr[k + 2] & 0xFF) != ((rgb >> 16) & 0xFF)
                            || (bgr[k + 1] & 0xFF) != ((rgb >> 8) & 0xFF)
                            || (bgr[k] & 0xFF) != (rgb & 0xFF)) {
                        distintos++;
                    }
                }
            }
        }
        double seg = (System.nanoTime() - t0) / 1e9;
        String resultado = ref == null ? "SOLO LECTURA (sin errores)"
                : distintos == 0 ? "OK (identico al JDK)" : "DIFERENTE en " + distintos + " pixeles";
        System.out.printf("%-28s %6d x %-6d | %6.1f s | %s%n", p.getFileName(), w, h, seg, resultado);
    }
}