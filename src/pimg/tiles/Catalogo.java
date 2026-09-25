package pimg.tiles;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imágenes disponibles: cada carpeta de data/tiles con un meta.json es una imagen lista. */
public final class Catalogo {
    public record Imagen(String id, PyramidLayout piramide, TileStore almacen, String formato) {}

    private static final String ID_VALIDO = "[A-Za-z0-9_-]{1,64}"; // PROTOCOLO.md §5.1
    private final Path raiz;

    public Catalogo(Path raiz) {
        this.raiz = raiz;
    }

    public List<Imagen> listar() throws IOException {
        List<Imagen> imagenes = new ArrayList<>();
        if (!Files.isDirectory(raiz)) {
            return imagenes;
        }
        try (DirectoryStream<Path> carpetas = Files.newDirectoryStream(raiz)) {
            for (Path carpeta : carpetas) {
                Imagen img = buscar(carpeta.getFileName().toString());
                if (img != null) {
                    imagenes.add(img);
                }
            }
        }
        imagenes.sort(Comparator.comparing(Imagen::id));
        return imagenes;
    }

    /** La imagen, o null si no existe o todavía no está lista. */
    public Imagen buscar(String id) throws IOException {
        if (!id.matches(ID_VALIDO)) {
            return null;                   // también impide rutas como "../../algo"
        }
        Path meta = raiz.resolve(id).resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        String json = Files.readString(meta);
        PyramidLayout piramide = new PyramidLayout(numero(json, "ancho"), numero(json, "alto"), numero(json, "tile"));
        return new Imagen(id, piramide, new TileStore(raiz, id), texto(json, "formato"));
    }

    // meta.json lo escribe nuestra ingesta con un formato fijo: basta una búsqueda simple
    private static int numero(String json, String clave) throws IOException {
        Matcher m = Pattern.compile("\"" + clave + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) {
            throw new IOException("meta.json sin el campo " + clave);
        }
        return Integer.parseInt(m.group(1));
    }

    private static String texto(String json, String clave) throws IOException {
        Matcher m = Pattern.compile("\"" + clave + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) {
            throw new IOException("meta.json sin el campo " + clave);
        }
        return m.group(1);
    }
}