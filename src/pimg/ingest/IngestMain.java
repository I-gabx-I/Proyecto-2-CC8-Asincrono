package pimg.ingest;

import pimg.tiles.PyramidLayout;
import pimg.tiles.TileStore;

import java.nio.file.Path;

public final class IngestMain {
    private static final int T = 256;
    private static final Path SALIDA = Path.of("data", "tiles");
    private static final float CALIDAD_JPEG = 0.85f;

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--plan")) {
            imprimirPiramide(new PyramidLayout(Integer.parseInt(args[1]), Integer.parseInt(args[2]), T));
        } else if (args.length == 2 && args[0].equals("--leer")) {
            try (ImageIOSource fuente = new ImageIOSource(Path.of(args[1]))) {
                medirLectura(fuente);
            }
        } else if (args.length == 2) {
            construir(Path.of(args[0]), args[1]);
        } else {
            System.out.println("Uso:");
            System.out.println("  IngestMain <imagen> <id>        construye la piramide en data/tiles/<id>");
            System.out.println("  IngestMain --leer <imagen>      solo mide la lectura");
            System.out.println("  IngestMain --plan <ancho> <alto>");
        }
    }

    private static void construir(Path archivo, String id) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {  // regla de PROTOCOLO.md §5.1
            throw new IllegalArgumentException("Identificador invalido: " + id);
        }
        int hilos = Runtime.getRuntime().availableProcessors();

        try (ImageIOSource fuente = new ImageIOSource(archivo)) {
            PyramidLayout piramide = new PyramidLayout(fuente.ancho(), fuente.alto(), T);
            TileStore destino = new TileStore(SALIDA, id);
            destino.prepararNiveles(piramide.niveles());

            System.out.println("Lector: " + fuente.descripcion() + " | hilos de compresion: " + hilos);
            imprimirPiramide(piramide);

            TileEncoderPool compresor = new TileEncoderPool(destino, CALIDAD_JPEG, hilos, hilos * 32);
            PyramidBuilder constructor = new PyramidBuilder(piramide, compresor);

            Runtime rt = Runtime.getRuntime();
            long memoriaMaxMB = 0;
            long inicio = System.nanoTime();
            int franjas = Math.ceilDiv(fuente.alto(), T);

            for (int i = 0; i < franjas; i++) {
                int y = i * T;
                constructor.agregarFranjaOriginal(fuente.leerFranja(y, Math.min(T, fuente.alto() - y)));

                memoriaMaxMB = Math.max(memoriaMaxMB, (rt.totalMemory() - rt.freeMemory()) >> 20);
                if (i % 10 == 0 || i == franjas - 1) {
                    System.out.printf("Franja %3d/%d | %6.1f s | %d tiles listos%n",
                            i + 1, franjas, (System.nanoTime() - inicio) / 1e9, compresor.tiles());
                }
            }
            constructor.terminar();
            compresor.terminar();
            destino.escribirMeta(piramide, "JPEG");

            double total = (System.nanoTime() - inicio) / 1e9;
            System.out.printf("%n----- RESUMEN -----%n");
            System.out.printf("Tiles generados: %d de %d esperados %s%n", compresor.tiles(), piramide.tilesTotales(),
                    compresor.tiles() == piramide.tilesTotales() ? "(OK)" : "(ERROR)");
            System.out.printf("Tiempo total: %.1f s | lectura: %.1f s | conversion: %.1f s%n",
                    total, fuente.segundosLectura(), fuente.segundosConversion());
            System.out.printf("Disco: %d MB (promedio %d KB/tile)%n",
                    compresor.bytes() >> 20, compresor.bytes() / Math.max(1, compresor.tiles()) / 1024);
            System.out.printf("Memoria max. observada: %d MB (limite %d MB)%n", memoriaMaxMB, rt.maxMemory() >> 20);
        }
    }

    private static void imprimirPiramide(PyramidLayout p) {
        System.out.printf("Imagen %d x %d | tile %d | %d niveles (z = 0..%d)%n",
                p.anchoOriginal(), p.altoOriginal(), p.tile(), p.niveles(), p.zMax());
        System.out.println(" z |      ancho x alto    | cols x filas |   tiles");
        for (int z = 0; z < p.niveles(); z++) {
            System.out.printf("%2d | %8d x %-8d | %4d x %-5d | %7d%n",
                    z, p.ancho(z), p.alto(z), p.columnas(z), p.filas(z), p.tiles(z));
        }
        System.out.printf("Total de tiles: %d%n%n", p.tilesTotales());
    }

    private static void medirLectura(ImageIOSource fuente) throws Exception {
        Runtime rt = Runtime.getRuntime();
        int franjas = Math.ceilDiv(fuente.alto(), T);
        long inicio = System.nanoTime();
        long memoriaMaxMB = 0;
        for (int i = 0; i < franjas; i++) {
            int y = i * T;
            fuente.leerFranja(y, Math.min(T, fuente.alto() - y));
            memoriaMaxMB = Math.max(memoriaMaxMB, (rt.totalMemory() - rt.freeMemory()) >> 20);
        }
        System.out.printf("Total: %.1f s | lectura: %.1f s | conversion: %.1f s (%s)%n",
                (System.nanoTime() - inicio) / 1e9,
                fuente.segundosLectura(), fuente.segundosConversion(), fuente.metodoConversion());
        System.out.printf("Memoria max. observada: %d MB (limite %d MB)%n", memoriaMaxMB, rt.maxMemory() >> 20);
    }
}