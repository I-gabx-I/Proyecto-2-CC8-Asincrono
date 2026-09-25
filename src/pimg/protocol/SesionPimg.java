package pimg.protocol;

import pimg.tiles.Catalogo;
import pimg.tiles.PyramidLayout;
import pimg.tiles.TileCache;
import pimg.websocket.WebSocketConnection;
import pimg.websocket.WebSocketListener;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Sesión PIMG de UN cliente: máquina de estados, tiles enviados y cola de envío con cancelación. */
public final class SesionPimg implements WebSocketListener {
    private enum Estado { CONNECTED, READY, IMAGE_OPEN, CLOSED }
    private enum Tipo { TILE, DONE }

    /** Algo pendiente de enviar: un tile, o el DONE que cierra un VIEWPORT. Solo coordenadas, no bytes. */
    private record Pedido(Tipo tipo, Catalogo.Imagen img, long seq, int z, int x, int y) {}

    private static final int VERSION = 1;
    private static final long MAX_SEQ = 0xFFFF_FFFFL;  // SEQ es uint32
private static final int COLA_MAX = 300;           // ≥ (4096/256 + 1)² + 1 DONE: cabe la vista máxima

    private static final long VISTA_MAX = 4096;        // px: impide pedir un nivel entero de golpe

    private final Catalogo catalogo;
    private final TileCache cache;
    private final int heartbeatSeg;
    private final int tamTile;

    private WebSocketConnection ws;
    private volatile Estado estado = Estado.CONNECTED;
    private volatile Catalogo.Imagen imagen;           // imagen abierta
    private long seqVigente = -1;                      // solo lo usa el hilo lector
    private final Set<Long> enviados = ConcurrentHashMap.newKeySet();

    private final ReentrantLock lockCola = new ReentrantLock();
    private final Condition hayTrabajo = lockCola.newCondition();
    private final ArrayDeque<Pedido> cola = new ArrayDeque<>();
    private Thread emisor;

    public SesionPimg(Catalogo catalogo, TileCache cache, int heartbeatSeg, int tamTile) {
        this.catalogo = catalogo;
        this.cache = cache;
        this.heartbeatSeg = heartbeatSeg;
        this.tamTile = tamTile;
    }

    // ======================= Eventos de la conexión =======================

    @Override
    public void alAbrir(WebSocketConnection c) {
        this.ws = c;
        this.emisor = Thread.ofVirtual().start(this::bucleEmisor);
        log("sesion PIMG creada");
    }

    @Override
    public void alRecibirTexto(WebSocketConnection c, String texto) throws IOException {
        try {
            Mensaje m = Mensaje.parsear(texto);
            switch (m.comando()) {
                case "HELLO"    -> hello(m);
                case "LIST"     -> { exigir(Estado.READY, Estado.IMAGE_OPEN); list(); }
                case "OPEN"     -> { exigir(Estado.READY, Estado.IMAGE_OPEN); open(m); }
                case "VIEWPORT" -> { exigir(Estado.IMAGE_OPEN); viewport(m); }
                case "GET_TILE" -> { exigir(Estado.IMAGE_OPEN); getTile(m); }
                case "EVICT"    -> { exigir(Estado.IMAGE_OPEN); evict(m); }
                case "CANCEL"   -> { exigir(Estado.IMAGE_OPEN); cancel(m); }
                default -> throw new PimgException(PimgException.MALFORMED, "Comando desconocido: " + m.comando());
            }
        } catch (PimgException e) {
            enviar(Mensaje.de("ERROR").con("CODE", e.codigo()).con("MSG", e.getMessage()));
            if (e.codigo() == PimgException.VERSION_UNSUPPORTED) {
                ws.cerrar(1002, "Version no soportada");   // §5.3: 426 cierra la conexión
            }
        }
    }

    @Override
    public void alRecibirBinario(WebSocketConnection c, byte[] datos) throws IOException {
        ws.cerrar(1003, "El cliente no envia binarios");   // §9: binario C->S no aceptado
    }

    @Override
    public void alCerrar(WebSocketConnection c, int codigo) {
        estado = Estado.CLOSED;
        if (emisor != null) {
            emisor.interrupt();
        }
        lockCola.lock();
        try {
            cola.clear();
        } finally {
            lockCola.unlock();
        }
        enviados.clear();                          // el servidor no conserva sesiones cerradas (§5.3)
        log("sesion cerrada (codigo " + codigo + ") | " + cache.estadisticas());
    }

    // ======================= Comandos =======================

    private void hello(Mensaje m) throws PimgException, IOException {
        exigir(Estado.CONNECTED);
        long v = m.entero("V", 0, 255);
        if (v != VERSION) {
            throw new PimgException(PimgException.VERSION_UNSUPPORTED, "Version no soportada: " + v);
        }
        m.entero("CACHE", 1, 1_000_000);           // se valida; en la demo el servidor se basa en EVICT
        estado = Estado.READY;
        enviar(Mensaje.de("HELLO_OK").con("V", VERSION).con("HB", heartbeatSeg).con("TS", tamTile));
    }

    private void list() throws PimgException, IOException {
        StringBuilder imgs = new StringBuilder();
        try {
            for (Catalogo.Imagen i : catalogo.listar()) {
                if (imgs.length() > 0) {
                    imgs.append(';');
                }
                imgs.append(i.id()).append(",READY,100");
            }
        } catch (IOException e) {
            throw new PimgException(PimgException.INTERNAL, "No se pudo leer el catalogo");
        }
        enviar(Mensaje.de("LIST_RESP").con("IMGS", imgs));
    }

    private void open(Mensaje m) throws PimgException, IOException {
        String id = m.texto("IMG");
        Catalogo.Imagen img;
        try {
            img = catalogo.buscar(id);
        } catch (IOException e) {
            throw new PimgException(PimgException.INTERNAL, "No se pudo leer la imagen " + id);
        }
        if (img == null) {
            throw new PimgException(PimgException.IMAGE_NOT_FOUND, "Imagen no encontrada: " + id);
        }
        lockCola.lock();
        try {
            cola.clear();                          // §5.3: OPEN vacía la cola...
        } finally {
            lockCola.unlock();
        }
        enviados.clear();                          // ...y el registro de enviados
        imagen = img;
        estado = Estado.IMAGE_OPEN;

        PyramidLayout p = img.piramide();
        enviar(Mensaje.de("META").con("IMG", id).con("W", p.anchoOriginal()).con("H", p.altoOriginal())
                .con("TS", p.tile()).con("L", p.niveles()).con("FMT", img.formato()));
        log("OPEN " + id);
    }

    private void viewport(Mensaje m) throws PimgException {
        long seq = m.entero("SEQ", 0, MAX_SEQ);
        if (seq <= seqVigente) {
            return;                                // §5.3: SEQ viejo o repetido -> se ignora
        }
        Catalogo.Imagen img = imagen;
        PyramidLayout p = img.piramide();
        int z = (int) m.entero("Z", 0, 255);
        if (z >= p.niveles()) {
            throw new PimgException(PimgException.OUT_OF_RANGE, "Nivel fuera de rango: " + z);
        }
        long x = m.entero("X", -VISTA_MAX, Integer.MAX_VALUE);
        long y = m.entero("Y", -VISTA_MAX, Integer.MAX_VALUE);
        long vw = m.entero("VW", 1, VISTA_MAX);
        long vh = m.entero("VH", 1, VISTA_MAX);

        List<Vista.Tile> visibles = Vista.tilesVisibles(p, z, x, y, vw, vh);
        int nuevos = 0;
        lockCola.lock();
        try {
            seqVigente = seq;
            cola.clear();                          // CANCELA todo lo pendiente de vistas anteriores
            for (Vista.Tile t : visibles) {
                if (enviados.contains(t.clave())) {
                    continue;                      // el cliente ya lo tiene
                }
                if (nuevos == COLA_MAX - 1) {
                    break;                         // vista enorme: se quedan los del centro
                }
                cola.addLast(new Pedido(Tipo.TILE, img, seq, t.z(), t.x(), t.y()));
                nuevos++;
            }
            cola.addLast(new Pedido(Tipo.DONE, img, seq, 0, 0, 0));
            hayTrabajo.signal();
        } finally {
            lockCola.unlock();
        }
        log(String.format("VIEWPORT seq=%d z=%d -> %d visibles, %d nuevos (%d ya enviados)",
                seq, z, visibles.size(), nuevos, visibles.size() - nuevos));
    }

    private void getTile(Mensaje m) throws PimgException {
        long seq = m.entero("SEQ", 0, MAX_SEQ);
        int z = (int) m.entero("Z", 0, 255);
        int x = (int) m.entero("X", 0, Integer.MAX_VALUE);
        int y = (int) m.entero("Y", 0, Integer.MAX_VALUE);
        Catalogo.Imagen img = imagen;
        if (!img.piramide().existe(z, x, y)) {
            throw new PimgException(PimgException.OUT_OF_RANGE, "Tile fuera de la piramide: " + z + "," + x + "," + y);
        }
        lockCola.lock();
        try {
            if (cola.size() >= COLA_MAX) {
                cola.pollFirst();                  // §11: cola llena -> se descarta el más antiguo
            }
            cola.addLast(new Pedido(Tipo.TILE, img, seq, z, x, y)); // se envía aunque ya se haya enviado
            hayTrabajo.signal();
        } finally {
            lockCola.unlock();
        }
    }

    private void evict(Mensaje m) throws PimgException {
        String lista = m.texto("TILES");
        if (lista.isEmpty()) {
            return;
        }
        for (String t : lista.split(";")) {
            String[] partes = t.split(",");
            try {
                if (partes.length != 3) {
                    throw new NumberFormatException();
                }
                enviados.remove(new Vista.Tile(Integer.parseInt(partes[0]),
                        Integer.parseInt(partes[1]), Integer.parseInt(partes[2])).clave());
            } catch (NumberFormatException e) {
                throw new PimgException(PimgException.MALFORMED, "Tile mal formado: " + t);
            }
        }
    }

    private void cancel(Mensaje m) throws PimgException {
        long seq = m.entero("SEQ", 0, MAX_SEQ);
        lockCola.lock();
        try {
            cola.removeIf(p -> p.seq() <= seq);    // §5.3: descarta todo con SEQ <= el indicado
        } finally {
            lockCola.unlock();
        }
    }

    // ======================= Hilo emisor =======================

    private void bucleEmisor() {
        long seqContado = -1;
        int enviadosEnSeq = 0;
        try {
            while (true) {
                Pedido p;
                lockCola.lock();
                try {
                    while (cola.isEmpty()) {
                        hayTrabajo.await();        // duerme sin gastar CPU hasta que haya trabajo
                    }
                    p = cola.pollFirst();
                } finally {
                    lockCola.unlock();
                }

                if (p.seq() != seqContado) {
                    seqContado = p.seq();
                    enviadosEnSeq = 0;
                }
                if (p.tipo() == Tipo.DONE) {
                    enviar(Mensaje.de("DONE").con("SEQ", p.seq()).con("SENT", enviadosEnSeq));
                    log(String.format("DONE seq=%d SENT=%d | %s", p.seq(), enviadosEnSeq, cache.estadisticas()));
                } else if (enviarTile(p)) {
                    enviadosEnSeq++;
                }
            }
        } catch (InterruptedException | IOException e) {
            // la sesión terminó o el socket se cerró
        }
    }

    private boolean enviarTile(Pedido p) throws IOException {
        Catalogo.Imagen img = p.img();
        byte[] datos;
        try {
            datos = cache.obtener(img.id(), img.almacen(), p.z(), p.x(), p.y());
        } catch (NoSuchFileException e) {
            enviar(Mensaje.de("ERROR").con("CODE", PimgException.INTERNAL)
                    .con("MSG", "Tile no disponible: " + p.z() + "," + p.x() + "," + p.y()));
            return false;
        }
        byte formato = img.formato().equals("PNG") ? TileFrame.FMT_PNG : TileFrame.FMT_JPEG;
        ws.enviarBinario(TileFrame.construir(p.seq(), p.z(), p.x(), p.y(), formato, datos));
        if (img == imagen) {
            enviados.add(new Vista.Tile(p.z(), p.x(), p.y()).clave()); // se marca AL ENVIAR, no al encolar
        }
        return true;
    }

    // ======================= Utilidades =======================

    private void exigir(Estado... permitidos) throws PimgException {
        for (Estado e : permitidos) {
            if (estado == e) {
                return;
            }
        }
        throw new PimgException(PimgException.INVALID_STATE, "Comando no permitido en estado " + estado);
    }

    private void enviar(Mensaje m) throws IOException {
        ws.enviarTexto(m.toString());
    }

    private void log(String mensaje) {
        System.out.printf("[%s] %s%n", ws == null ? "?" : ws.cliente(), mensaje);
    }
}