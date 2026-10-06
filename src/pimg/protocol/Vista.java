package pimg.protocol;

import pimg.tiles.PyramidLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tiles visibles de una vista (PROTOCOLO.md §6), ordenados del centro hacia afuera. */
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
        tiles.sort(Comparator.comparingDouble(t -> distancia(T, t, vx, vy, vw, vh)));
        return tiles;
    }

    /**
     * Distancia del centro del tile al centro de la vista, en unidades de tile (§6):
     * dist = √[((x + 0.5)·T − (X + VW/2))² + ((y + 0.5)·T − (Y + VH/2))²] / T.
     * La usan el orden de tilesVisibles y los plazos de EDF (§14.2): una sola fórmula para ambos.
     */
    public static double distancia(int T, Tile t, long vx, long vy, long vw, long vh) {
        double dx = (t.x() + 0.5) * T - (vx + vw / 2.0);
        double dy = (t.y() + 0.5) * T - (vy + vh / 2.0);
        return Math.sqrt(dx * dx + dy * dy) / T;
    }
}