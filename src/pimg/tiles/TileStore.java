package pimg.tiles;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Formato de la pirámide en disco (PROTOCOLO.md §22.2):
 *   {raiz}/{id}/meta.json   se escribe al final: su existencia significa READY
 *   {raiz}/{id}/{z}.pack    tiles del nivel z concatenados, en orden de llegada
 *   {raiz}/{id}/{z}.idx     índice denso: 16 B de cabecera + 12 B por tile (offset + longitud)
 *
 * Es la ÚNICA clase que conoce este formato: la ingesta escribe con ella y el servidor lee con ella.
 */
public final class TileStore {
    static final byte[] FIRMA_IDX = {'P', 'I', 'D', 'X'};
    static final byte VERSION_IDX = 1;
    static final int CABECERA_IDX = 16;
    static final int ENTRADA_IDX = 12;     // offset (8 B) + longitud (4 B)

    private final Path carpeta;
    private final String formato;          // "PNG" o "JPEG", tal como queda en meta.json

    // ---- Estado de escritura (solo durante la ingesta) ----
    private PyramidLayout piramide;
    private FileChannel[] packs;           // un .pack por nivel
    private AtomicLong[] siguiente;        // próxima posición libre en cada .pack
    private AtomicLongArray[] offsets;     // índice en memoria: [z] -> posición (y * columnas + x)
    private AtomicIntegerArray[] longitudes;

    // ---- Estado de lectura (servidor): se carga la primera vez que se pide un tile ----
    private final ReentrantLock lockCarga = new ReentrantLock();
    private volatile boolean cargado = false;
    private volatile boolean cerrado = false;     // reingesta en curso: no volver a abrir los archivos (§22.4)
    private FileChannel[] packsLectura;
    private int[] columnasLectura;
    private long[][] offsetsLectura;
    private int[][] longitudesLectura;

    public TileStore(Path raiz, String idImagen, String formato) {
        this.carpeta = raiz.resolve(idImagen);
        this.formato = formato.toUpperCase();
    }

    public Path carpeta()   { return carpeta; }
    public String formato() { return formato; }

    // =========================== ESCRITURA ===========================

    /** Crea la carpeta, abre un .pack por nivel y prepara el índice en memoria. */
    public void prepararEscritura(PyramidLayout p) throws IOException {
        Files.createDirectories(carpeta);
        int niveles = p.niveles();
        piramide = p;
        packs = new FileChannel[niveles];
        siguiente = new AtomicLong[niveles];
        offsets = new AtomicLongArray[niveles];
        longitudes = new AtomicIntegerArray[niveles];
        for (int z = 0; z < niveles; z++) {
            packs[z] = FileChannel.open(carpeta.resolve(z + ".pack"), CREATE, WRITE, TRUNCATE_EXISTING);
            siguiente[z] = new AtomicLong();
            int n = Math.toIntExact(p.tiles(z));
            offsets[z] = new AtomicLongArray(n);
            longitudes[z] = new AtomicIntegerArray(n);       // 0 = tile no escrito
        }
    }

    /**
     * Guarda un tile. La llaman varios hilos a la vez, sin lock:
     * 1) getAndAdd reserva un hueco propio en el .pack (operación atómica: nunca dos hilos con el mismo hueco);
     * 2) la escritura posicional escribe en ese hueco sin mover un cursor compartido.
     */
    public void escribir(int z, int x, int y, byte[] datos) throws IOException {
        long pos = siguiente[z].getAndAdd(datos.length);
        ByteBuffer buf = ByteBuffer.wrap(datos);
        long p = pos;
        while (buf.hasRemaining()) {
            p += packs[z].write(buf, p);                     // puede escribir menos de lo pedido: se repite
        }
        int i = y * piramide.columnas(z) + x;
        offsets[z].set(i, pos);
        longitudes[z].set(i, datos.length);
    }

    /** Vuelca el índice de cada nivel a su .idx y cierra los .pack. Se llama cuando ya no quedan tiles por escribir. */
    public void cerrarEscritura() throws IOException {
        for (int z = 0; z < packs.length; z++) {
            escribirIndice(z);
            packs[z].close();
        }
    }

    private void escribirIndice(int z) throws IOException {
        int n = offsets[z].length();
        ByteBuffer b = ByteBuffer.allocate(CABECERA_IDX + n * ENTRADA_IDX);   // ByteBuffer es big-endian por defecto
        b.put(FIRMA_IDX).put(VERSION_IDX).put(new byte[3])                    // firma, versión, reservado
         .putInt(piramide.columnas(z)).putInt(piramide.filas(z));
        for (int i = 0; i < n; i++) {
            b.putLong(offsets[z].get(i)).putInt(longitudes[z].get(i));
        }
        b.flip();
        try (FileChannel idx = FileChannel.open(carpeta.resolve(z + ".idx"), CREATE, WRITE, TRUNCATE_EXISTING)) {
            while (b.hasRemaining()) {
                idx.write(b);
            }
        }
    }

    public void escribirMeta(PyramidLayout p) throws IOException {
        String json = String.format("{\"ancho\":%d,\"alto\":%d,\"tile\":%d,\"niveles\":%d,\"formato\":\"%s\"}%n",
                p.anchoOriginal(), p.altoOriginal(), p.tile(), p.niveles(), formato);
        Files.writeString(carpeta.resolve("meta.json"), json, StandardCharsets.UTF_8);
    }

    // =========================== LECTURA ===========================

    /**
     * Devuelve los bytes codificados del tile (z, x, y). La llaman muchas sesiones a la vez:
     * la lectura posicional no mueve un cursor compartido, así que no necesita lock.
     */
    public byte[] leer(int z, int x, int y) throws IOException {
        if (cerrado) {
            throw new ClosedChannelException();           // la imagen se está regenerando
        }
        cargarIndices();
        if (z < 0 || z >= packsLectura.length) {
            throw new NoSuchFileException("Nivel inexistente: " + z);
        }
        int i = y * columnasLectura[z] + x;
        int largo = longitudesLectura[z][i];
        if (largo == 0) {
            throw new NoSuchFileException("Tile no escrito: " + z + "," + x + "," + y);
        }
        for (int intento = 0; ; intento++) {
            FileChannel canal = packsLectura[z];
            try {
                return leerDe(canal, offsetsLectura[z][i], largo, z);
            } catch (ClosedByInterruptException e) {
                throw e;                   // ESTE hilo fue interrumpido (su sesión se cerró): no se reintenta
            } catch (ClosedChannelException e) {
                // Otra sesión cerró el canal COMPARTIDO: Java cierra un FileChannel cuando se interrumpe
                // a un hilo que está leyendo de él (al cerrar una pestaña se interrumpe a su emisor).
                if (cerrado || intento == 2) {
                    throw e;
                }
                reabrir(z, canal);
            }
        }
    }

    private static byte[] leerDe(FileChannel canal, long offset, int largo, int z) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(largo);
        long p = offset;
        while (buf.hasRemaining()) {
            int n = canal.read(buf, p);
            if (n < 0) {
                throw new EOFException("Pack truncado en el nivel " + z);
            }
            p += n;
        }
        return buf.array();
    }

    /** Reabre el .pack del nivel z, solo si nadie lo reabrió ya y la imagen no se está regenerando. */
    private void reabrir(int z, FileChannel cerradoAntes) throws IOException {
        lockCarga.lock();
        try {
            if (!cerrado && packsLectura[z] == cerradoAntes) {
                packsLectura[z] = FileChannel.open(carpeta.resolve(z + ".pack"), READ);
            }
        } finally {
            lockCarga.unlock();      // el lock también publica el canal nuevo a los demás hilos
        }
    }

    /**
     * Antes de una reingesta (§22.4): cierra los .pack abiertos para leer. Las lecturas que sigan en curso
     * fallan con IOException, y ninguna vuelve a cargar los índices a medio escribir de la ingesta nueva.
     */
    public void cerrarLectura() {
        lockCarga.lock();
        try {
            cerrado = true;
            if (cargado) {
                for (FileChannel c : packsLectura) {
                    try {
                        c.close();
                    } catch (IOException e) {
                        // se está cerrando de todos modos
                    }
                }
            }
        } finally {
            lockCarga.unlock();
        }
    }

    /**
     * Carga todos los .idx en RAM y abre los .pack, UNA sola vez (la primera vez que se pide un tile).
     * Doble verificación: si ya está cargado no se toma el lock; si no, solo un hilo carga y los demás esperan.
     */
    private void cargarIndices() throws IOException {
        if (cargado) {
            return;
        }
        lockCarga.lock();
        try {
            if (cargado) {
                return;
            }
            if (cerrado) {
                throw new ClosedChannelException();
            }
            List<FileChannel> canales = new ArrayList<>();
            List<long[]> offs = new ArrayList<>();
            List<int[]> lens = new ArrayList<>();
            List<Integer> cols = new ArrayList<>();
            for (int z = 0; Files.isRegularFile(carpeta.resolve(z + ".idx")); z++) {
                ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(carpeta.resolve(z + ".idx")));
                byte[] firma = new byte[4];
                b.get(firma);
                int version = b.get();
                b.position(8);                                    // saltar el reservado
                int c = b.getInt();
                int f = b.getInt();
                if (!Arrays.equals(firma, FIRMA_IDX) || version != VERSION_IDX
                        || b.capacity() != CABECERA_IDX + (long) c * f * ENTRADA_IDX) {
                    throw new IOException("Indice invalido: " + carpeta.resolve(z + ".idx"));
                }
                long[] o = new long[c * f];
                int[] l = new int[c * f];
                for (int i = 0; i < o.length; i++) {
                    o[i] = b.getLong();
                    l[i] = b.getInt();
                }
                canales.add(FileChannel.open(carpeta.resolve(z + ".pack"), READ));
                offs.add(o);
                lens.add(l);
                cols.add(c);
            }
            if (canales.isEmpty()) {
                throw new NoSuchFileException("No hay indices en " + carpeta);
            }
            packsLectura = canales.toArray(new FileChannel[0]);
            offsetsLectura = offs.toArray(new long[0][]);
            longitudesLectura = lens.toArray(new int[0][]);
            columnasLectura = cols.stream().mapToInt(Integer::intValue).toArray();
            cargado = true;                       // volatile: publica todo lo anterior a los demás hilos
        } finally {
            lockCarga.unlock();
        }
    }
}