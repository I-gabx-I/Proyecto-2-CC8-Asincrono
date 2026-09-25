package pimg.tiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Formato de la pirámide en disco: {raiz}/{id}/{z}/{x}_{y}.jpg + {raiz}/{id}/meta.json */
public final class TileStore {
    private final Path carpeta;

    public TileStore(Path raiz, String idImagen) {
        this.carpeta = raiz.resolve(idImagen);
    }

    public Path carpeta() { return carpeta; }

    public Path ruta(int z, int x, int y) {
        return carpeta.resolve(Integer.toString(z)).resolve(x + "_" + y + ".jpg");
    }

    /** Crea las carpetas de todos los niveles una sola vez, no en cada tile. */
    public void prepararNiveles(int niveles) throws IOException {
        for (int z = 0; z < niveles; z++) {
            Files.createDirectories(carpeta.resolve(Integer.toString(z)));
        }
    }

    public void escribir(int z, int x, int y, byte[] datos) throws IOException {
        Files.write(ruta(z, x, y), datos);
    }

    public byte[] leer(int z, int x, int y) throws IOException {
        return Files.readAllBytes(ruta(z, x, y));
    }

    public void escribirMeta(PyramidLayout p, String formato) throws IOException {
        String json = String.format("{\"ancho\":%d,\"alto\":%d,\"tile\":%d,\"niveles\":%d,\"formato\":\"%s\"}%n",
                p.anchoOriginal(), p.altoOriginal(), p.tile(), p.niveles(), formato);
        Files.writeString(carpeta.resolve("meta.json"), json, StandardCharsets.UTF_8);
    }
}