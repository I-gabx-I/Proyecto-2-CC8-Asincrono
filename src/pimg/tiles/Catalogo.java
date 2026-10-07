package pimg.tiles;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imágenes disponibles: cada carpeta de data/tiles con un meta.json es una imagen READY.
 * La ingesta automática (§22.4) marca aquí las que están en PROCESSING (con su %) o en FAILED.
 */
public final class Catalogo {
    /** version distingue una imagen regenerada de la anterior con el mismo id (claves de TileCache). */
    public record Imagen(String id, PyramidLayout piramide, TileStore almacen, String formato, int version) {
        public String claveCache() {
            return id + "#" + version;      // los tiles viejos que queden en caché no se vuelven a servir
        }
    }

    /** Una fila de LIST_RESP (§6): READY, PROCESSING con su porcentaje, o FAILED. */
    public record Entrada(String id, String estado, int progreso) {}

    private static final String ID_VALIDO = "[A-Za-z0-9_-]{1,64}"; // PROTOCOLO.md §5.1
    private final Path raiz;
    private final ConcurrentHashMap<String, Imagen> abiertas = new ConcurrentHashMap<>();    // un TileStore por imagen
    private final ConcurrentHashMap<String, Entrada> enIngesta = new ConcurrentHashMap<>();  // PROCESSING o FAILED
    private final ReentrantLock lock = new ReentrantLock();   // abrir una imagen del disco vs. empezar su reingesta
    private final AtomicInteger versiones = new AtomicInteger();

    public Catalogo(Path raiz) {
        this.raiz = raiz;
    }

    public List<Entrada> listar() throws IOException {
        Map<String, Entrada> todas = new TreeMap<>();            // ordenadas por id
        if (Files.isDirectory(raiz)) {
            try (DirectoryStream<Path> carpetas = Files.newDirectoryStream(raiz)) {
                for (Path carpeta : carpetas) {
                    String id = carpeta.getFileName().toString();
                    if (id.matches(ID_VALIDO) && Files.isRegularFile(carpeta.resolve("meta.json"))) {
                        todas.put(id, new Entrada(id, "READY", 100));
                    }
                }
            }
        }
        todas.putAll(enIngesta);          // PROCESSING o FAILED manda sobre un meta.json que todavía no se borró
        return new ArrayList<>(todas.values());
    }

    /** "PROCESSING" o "FAILED" si la imagen no se puede abrir (409); null si está lista o no existe. */
    public String estadoNoListo(String id) {
        Entrada e = enIngesta.get(id);
        return e == null ? null : e.estado();
    }

    /** La imagen, o null si no existe o todavía no está lista. */
    public Imagen buscar(String id) throws IOException {
        if (!id.matches(ID_VALIDO) || enIngesta.containsKey(id)) {
            return null;                   // el patrón también impide rutas como "../../algo"
        }
        Imagen yaAbierta = abiertas.get(id);
        if (yaAbierta != null) {
            return yaAbierta;               // misma imagen = mismo TileStore = índice cargado una sola vez
        }
        lock.lock();
        try {
            if (enIngesta.containsKey(id)) {
                return null;                // empezó una reingesta mientras se esperaba el lock
            }
            yaAbierta = abiertas.get(id);
            if (yaAbierta != null) {
                return yaAbierta;           // otro hilo la abrió mientras se esperaba el lock
            }
            Path meta = raiz.resolve(id).resolve("meta.json");
            if (!Files.isRegularFile(meta)) {
                return null;
            }
            String json = Files.readString(meta);
            PyramidLayout piramide = new PyramidLayout(numero(json, "ancho"), numero(json, "alto"), numero(json, "tile"));
            String formato = texto(json, "formato");
            Imagen nueva = new Imagen(id, piramide, new TileStore(raiz, id, formato), formato, versiones.incrementAndGet());
            abiertas.put(id, nueva);
            return nueva;
        } finally {
            lock.unlock();
        }
    }

    // ============ Ingesta automática (§22.4): la llama pimg.ingest.IngestaAutomatica ============

    /** La imagen pasa a PROCESSING 0 %; si estaba abierta, se cierran sus .pack antes de que se reescriban. */
    public void empezarIngesta(String id) {
        lock.lock();
        try {
            enIngesta.put(id, new Entrada(id, "PROCESSING", 0));
            Imagen vieja = abiertas.remove(id);
            if (vieja != null) {
                vieja.almacen().cerrarLectura();
            }
        } finally {
            lock.unlock();
        }
    }

    public void progreso(String id, int porcentaje) {
        enIngesta.computeIfPresent(id, (clave, e) -> new Entrada(clave, "PROCESSING", porcentaje));
    }

    /** Si terminó bien, deja de estar marcada y se vuelve a abrir desde su meta.json nuevo. */
    public void terminarIngesta(String id, boolean ok) {
        if (ok) {
            enIngesta.remove(id);
        } else {
            enIngesta.put(id, new Entrada(id, "FAILED", 0));
        }
    }

    /** El archivo desapareció antes de empezar: la imagen vuelve a lo que haya en disco. */
    public void cancelarIngesta(String id) {
        enIngesta.remove(id);
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