import pimg.tiles.Catalogo;
import pimg.tiles.PyramidLayout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

/**
 * Prueba de la Fase 2: lee TODOS los tiles de una pirámide desde el paquete.
 *   ProbarAlmacen <id>                  cada tile se lee, se decodifica y tiene el tamaño correcto
 *   ProbarAlmacen <id> <original.png>   además, el nivel máximo es idéntico al original (solo imágenes chicas)
 */
public class ProbarAlmacen {
    public static void main(String[] args) throws Exception {
        Catalogo catalogo = new Catalogo(Path.of("data", "tiles"));
        Catalogo.Imagen img = catalogo.buscar(args[0]);
        if (img == null) {
            System.out.println("No existe o no esta lista: " + args[0]);
            return;
        }
        PyramidLayout p = img.piramide();
        int T = p.tile();
        BufferedImage original = args.length > 1 ? ImageIO.read(Path.of(args[1]).toFile()) : null;

        long tiles = 0, malos = 0, distintos = 0;
        long t0 = System.nanoTime();
        for (int z = 0; z < p.niveles(); z++) {
            for (int y = 0; y < p.filas(z); y++) {
                for (int x = 0; x < p.columnas(z); x++) {
                    BufferedImage tile = ImageIO.read(new ByteArrayInputStream(img.almacen().leer(z, x, y)));
                    int tw = Math.min(T, p.ancho(z) - x * T);
                    int th = Math.min(T, p.alto(z) - y * T);
                    tiles++;
                    if (tile == null || tile.getWidth() != tw || tile.getHeight() != th) {
                        malos++;
                        System.out.printf("MAL: tile %d,%d,%d%n", z, x, y);
                        continue;
                    }
                    if (original != null && z == p.zMax()) {
                        for (int ty = 0; ty < th; ty++) {
                            for (int tx = 0; tx < tw; tx++) {
                                if ((tile.getRGB(tx, ty) & 0xFFFFFF) != (original.getRGB(x * T + tx, y * T + ty) & 0xFFFFFF)) {
                                    distintos++;
                                }
                            }
                        }
                    }
                }
            }
        }
        double seg = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%d tiles leidos en %.1f s (%.1f ms/tile) | con problemas: %d%n",
                tiles, seg, seg * 1000 / tiles, malos);
        if (original != null) {
            System.out.println(distintos == 0
                    ? "Nivel maximo IDENTICO al original"
                    : "Nivel maximo DIFERENTE en " + distintos + " pixeles");
        }
    }
}