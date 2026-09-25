package pimg.protocol;

import pimg.tiles.PyramidLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tiles visibles de una vista (PROTOCOLO.md §4.3), ordenados del centro hacia afuera. */
public final class Vista {
    private Vista() {}

    public record Tile(int z, int x, int y) {
        /** Clave única de 64 bits: z (8 bits) | x (28 bits) | y (28 bits). */
        public long clave() {
            return ((long) z << 56) | ((long) x << 28) | y;
        }
    }

    public static List<Tile> tilesVisibles(PyramidLayout p, int z, long vx, long vy, long vw, long vh) {
        int T = p.tile();
        int x0 = (int) Math.max(0, Math.floorDiv(vx, T));      // floorDiv: correcto con X negativo
        int x1 = (int) Math.min(p.columnas(z) - 1, Math.floorDiv(vx + vw - 1, T));
        int y0 = (int) Math.max(0, Math.floorDiv(vy, T));
        int y1 = (int) Math.min(p.filas(z) - 1, Math.floorDiv(vy + vh - 1, T));

        List<Tile> tiles = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                tiles.add(new Tile(z, x, y));
            }
        }
        double cx = vx + vw / 2.0;
        double cy = vy + vh / 2.0;
        tiles.sort(Comparator.comparingDouble(t -> {
            double dx = (t.x() + 0.5) * T - cx;
            double dy = (t.y() + 0.5) * T - cy;
            return dx * dx + dy * dy;                          // distancia² al centro de la vista
        }));
        return tiles;
    }
}