package pimg.protocol;

import pimg.tiles.Catalogo;
import pimg.tiles.PyramidLayout;
import pimg.tiles.TileCache;
import pimg.transporte.ControladorPI;
import pimg.transporte.FecXor;
import pimg.transporte.FiltroBloom;
import pimg.transporte.PlanificadorEDF;
import pimg.websocket.WebSocketConnection;
import pimg.websocket.WebSocketListener;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Sesión PIMG de UN cliente: máquina de estados, estado de su caché (Bloom) y cola de envío con cancelación. */
public final class SesionPimg implements WebSocketListener {
    private enum Estado { CONNECTED, READY, IMAGE_OPEN, CLOSED }
    private enum Tipo { TILE, PARIDAD, DONE }

    /**
     * Algo pendiente de enviar: un tile, una paridad o el DONE que cierra un VIEWPORT. Solo coordenadas,
     * no bytes. grupo = miembros de una PARIDAD (null en TILE y DONE).
     */
    private record Pedido(Tipo tipo, Catalogo.Imagen img, long seq, int z, int x, int y, List<Vista.Tile> grupo) {}

    private static final int VERSION = 2;
    private static final long MAX_SEQ = 0xFFFF_FFFFL;  // SEQ es uint32
    private static final int COLA_MAX = 300;           // ≥ (4096/256 + 1)² + 4 paridades + 1 DONE = 294

    private static final long VISTA_MAX = 4096;        // px: impide pedir un nivel entero de golpe

    private final Catalogo catalogo;
    private final TileCache cache;
    private final int heartbeatSeg;
    private final int tamTile;
    private final boolean redSimulada;                 // servidor iniciado con --sim (§16)
    private final boolean controlRitmo;                // false con --sin-pi: el emisor no espera entre tiles

    private WebSocketConnection ws;
    private Enlace enlace;                             // salida al socket, directa o simulada
    private volatile Estado estado = Estado.CONNECTED;
    private volatile Catalogo.Imagen imagen;           // imagen abierta
    private long seqVigente = -1;                      // solo lo usa el hilo lector

    // Lo que tiene el cliente (§13.4), protegido por lockCola: su último filtro de Bloom más lo enviado después
    private FiltroBloom filtro = new FiltroBloom(0);
    private final LinkedHashMap<Long, Long> enviadosRecientes = new LinkedHashMap<>();   // clave -> NUM, en orden de envío

    private final ReentrantLock lockCola = new ReentrantLock();
    private final Condition hayTrabajo = lockCola.newCondition();
    private final PlanificadorEDF<Pedido> cola = new PlanificadorEDF<>();   // EDF (§14), protegida por lockCola
    private Thread emisor;
    private volatile long num = 0;                     // NUM (§8.3): lo escribe solo el emisor; lo lee REPORT

    // Control del ritmo (§12). El controlador lo usa solo el hilo lector (REPORT); el emisor lee la tasa.
    private final ControladorPI pi = new ControladorPI();
    private volatile double tasa = pi.tasa();          // R vigente, mensajes binarios por segundo
    private volatile long ultimoReporte = 0;           // nanoTime del último REPORT (0 = todavía ninguno)
    private long proximoEnvio = 0;                     // solo el emisor: no enviar otro binario antes de esto

    public SesionPimg(Catalogo catalogo, TileCache cache, int heartbeatSeg, int tamTile,
                       boolean redSimulada, boolean controlRitmo) {
        this.catalogo = catalogo;
        this.cache = cache;
        this.heartbeatSeg = heartbeatSeg;
        this.tamTile = tamTile;
        this.redSimulada = redSimulada;
        this.controlRitmo = controlRitmo;
    }

    // ======================= Eventos de la conexión =======================

    @Override
    public void alAbrir(WebSocketConnection c) {
        this.ws = c;
        this.enlace = new Enlace(c, redSimulada);
        this.enlace.iniciar();
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
                case "RESUME"   -> { exigir(Estado.READY); resume(m); }
                case "BLOOM"    -> { exigir(Estado.IMAGE_OPEN); bloom(m); }
                case "CANCEL"   -> { exigir(Estado.IMAGE_OPEN); cancel(m); }
                case "SIM"      -> { exigir(Estado.READY, Estado.IMAGE_OPEN); sim(m); }
                case "REPORT"   -> { exigir(Estado.IMAGE_OPEN); report(m); }
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
        if (enlace != null) {
            enlace.cerrar();
        }
        lockCola.lock();
        try {
            cola.vaciar();
            enviadosRecientes.clear();             // el servidor no conserva sesiones cerradas (§5.3)
        } finally {
            lockCola.unlock();
        }
        log("sesion cerrada (codigo " + codigo + ") | " + cache.estadisticas());
    }

    // ======================= Comandos =======================

    private void hello(Mensaje m) throws PimgException, IOException {
        exigir(Estado.CONNECTED);
        long v = m.entero("V", 0, 255);
        if (v != VERSION) {
            throw new PimgException(PimgException.VERSION_UNSUPPORTED, "Version no soportada: " + v);
        }
        m.entero("CACHE", 1, 1_000_000);           // se valida; lo que tiene el cliente lo dice su filtro
        estado = Estado.READY;
        enviar(Mensaje.de("HELLO_OK").con("V", VERSION).con("HB", heartbeatSeg).con("TS", tamTile)
                .con("BM", FiltroBloom.M).con("BK", FiltroBloom.K).con("RPT", 100));
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
        abrir(m.texto("IMG"), new FiltroBloom(0), false);         // filtro vacío: el cliente no tiene nada
    }

    /** RESUME (§13.6): como OPEN, pero el cliente conserva su caché y la describe con su filtro. */
    private void resume(Mensaje m) throws PimgException, IOException {
        String id = m.texto("IMG");
        int semilla = (int) m.entero("SEM", 0, MAX_SEQ);
        abrir(id, new FiltroBloom(semilla, bits(m)), true);
    }

    private void abrir(String id, FiltroBloom inicial, boolean reanudada) throws PimgException, IOException {
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
            cola.vaciar();                         // §7.3: vacía la cola...
            filtro = inicial;                      // ...y lo que se sabe de la caché del cliente
            enviadosRecientes.clear();
        } finally {
            lockCola.unlock();
        }
        imagen = img;
        estado = Estado.IMAGE_OPEN;

        PyramidLayout p = img.piramide();
        enviar(Mensaje.de("META").con("IMG", id).con("W", p.anchoOriginal()).con("H", p.altoOriginal())
                .con("TS", p.tile()).con("L", p.niveles()).con("FMT", img.formato()).con("RES", reanudada ? 1 : 0));
        log(reanudada ? "RESUME " + id + " | filtro con " + inicial.bitsEncendidos() + " bits en 1" : "OPEN " + id);
    }

    /** BLOOM (§13.4): reemplaza el filtro y olvida lo enviado con NUM ≤ MAX; desde ahora eso lo decide el filtro. */
    private void bloom(Mensaje m) throws PimgException {
        long max = m.entero("MAX", 0, MAX_SEQ);
        int semilla = (int) m.entero("SEM", 0, MAX_SEQ);
        FiltroBloom nuevo = new FiltroBloom(semilla, bits(m));
        int olvidados = 0;
        int quedan;
        lockCola.lock();
        try {
            filtro = nuevo;
            Iterator<Long> it = enviadosRecientes.values().iterator();   // en orden de NUM creciente
            while (it.hasNext() && it.next() <= max) {
                it.remove();
                olvidados++;
            }
            quedan = enviadosRecientes.size();
        } finally {
            lockCola.unlock();
        }
        log(String.format("BLOOM max=%d sem=%d | %d bits en 1 | %d envios ya cubiertos, %d posteriores",
                max, semilla, nuevo.bitsEncendidos(), olvidados, quedan));
    }

    /** BITS (§7.2): Base64 de exactamente 512 bytes; si no, 400. */
    private static byte[] bits(Mensaje m) throws PimgException {
        try {
            byte[] b = Base64.getDecoder().decode(m.texto("BITS"));
            if (b.length == FiltroBloom.BYTES) {
                return b;
            }
        } catch (IllegalArgumentException e) {
            // cae al error de abajo
        }
        throw new PimgException(PimgException.MALFORMED, "BITS debe ser Base64 de " + FiltroBloom.BYTES + " bytes");
    }

    /** §13.4: el cliente tiene t si su filtro lo dice o si se le envió después de esa instantánea. Con lockCola. */
    private boolean tiene(Vista.Tile t) {
        return filtro.contiene(t.z(), t.x(), t.y()) || enviadosRecientes.containsKey(t.clave());
    }

    private void viewport(Mensaje m) throws PimgException {
        long t0 = System.nanoTime();               // instante de llegada: base de los plazos (§14.2)
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
        int paridades = 0;
        List<Vista.Tile> protegidos = new ArrayList<>();   // FEC: los primeros nuevos, en orden de prioridad
        List<Long> plazosProtegidos = new ArrayList<>();
        lockCola.lock();
        try {
            seqVigente = seq;
            cola.vaciar();                         // CANCELA todo lo pendiente de vistas anteriores
            for (Vista.Tile t : visibles) {
                if (tiene(t)) {
                    continue;                      // el cliente ya lo tiene (o está en camino)
                }
                if (nuevos == COLA_MAX - 5) {      // deja lugar a 4 paridades y al DONE
                    break;                         // vista enorme: se quedan los del centro
                }
                double dist = Vista.distancia(p.tile(), t, x, y, vw, vh);
                long plazo = PlanificadorEDF.plazoTile(t0, dist);
                cola.agregar(new Pedido(Tipo.TILE, img, seq, t.z(), t.x(), t.y(), null), plazo);
                if (protegidos.size() < FecXor.PROTEGIDOS) {
                    protegidos.add(t);
                    plazosProtegidos.add(plazo);
                }
                nuevos++;
            }
            // FEC (§11.3 y §11.4): una PARIDAD por grupo entrelazado. Los plazos crecen con el rango,
            // así que el último miembro tiene el mayor: la paridad sale justo después de él.
            for (int[] rangos : FecXor.grupos(protegidos.size())) {
                List<Vista.Tile> grupo = new ArrayList<>();
                for (int r : rangos) {
                    grupo.add(protegidos.get(r));
                }
                long plazo = plazosProtegidos.get(rangos[rangos.length - 1]) + 1_000_000L;
                cola.agregar(new Pedido(Tipo.PARIDAD, img, seq, 0, 0, 0, grupo), plazo);
                paridades++;
            }
            cola.agregar(new Pedido(Tipo.DONE, img, seq, 0, 0, 0, null), PlanificadorEDF.SIN_PLAZO);
            hayTrabajo.signal();
        } finally {
            lockCola.unlock();
        }
        log(String.format("VIEWPORT seq=%d z=%d -> %d visibles, %d nuevos (%d ya los tiene), %d paridades",
                seq, z, visibles.size(), nuevos, visibles.size() - nuevos, paridades));
    }

    private void cancel(Mensaje m) throws PimgException {
        long seq = m.entero("SEQ", 0, MAX_SEQ);
        lockCola.lock();
        try {
            cola.quitarSi(p -> p.seq() <= seq);    // §7.3: descarta todo con SEQ <= el indicado
        } finally {
            lockCola.unlock();
        }
    }

    private void sim(Mensaje m) throws PimgException, IOException {
        if (!enlace.simulado()) {
            throw new PimgException(PimgException.SIM_DISABLED, "Servidor iniciado sin --sim");
        }
        int perdida = (int) m.entero("PERD", 0, 50);
        int ancho = (int) m.entero("BW", 0, 1_000_000);
        int latencia = (int) m.entero("LAT", 0, 2000);
        enlace.configurar(perdida, ancho, latencia);
        enviar(Mensaje.de("SIM_OK").con("PERD", perdida).con("BW", ancho).con("LAT", latencia));
        log(String.format("SIM perdida=%d %% ancho=%d KB/s latencia=%d ms", perdida, ancho, latencia));
    }

    /** REPORT (§12.2): medición del receptor. Actualiza el PI y responde CTRL (§12.7). No es un ACK. */
    private void report(Mensaje m) throws PimgException, IOException {
        long max = m.entero("MAX", 0, MAX_SEQ);
        m.entero("PERD", 0, MAX_SEQ);
        long colaCliente = m.entero("COLA", 0, 10_000);
        m.entero("DEC", 0, 60_000);
        m.entero("JIT", 0, 60_000);
        m.entero("REC", 0, MAX_SEQ);

        long q = Math.max(0, num - max) + colaCliente;   // en camino + esperando decodificarse (§12.3)
        long ahora = System.nanoTime();
        double dt = ultimoReporte == 0 ? 0.1 : Math.min(0.5, (ahora - ultimoReporte) / 1e9);
        boolean hayDemanda;
        double tarde;
        lockCola.lock();
        try {
            hayDemanda = !cola.estaVacia();
            tarde = cola.porcentajeTarde();
        } finally {
            lockCola.unlock();
        }
        double r = pi.actualizar(q, dt, hayDemanda);
        tasa = r;
        ultimoReporte = ahora;
        enviar(Mensaje.de("CTRL").con("R", controlRitmo ? String.format(Locale.ROOT, "%.1f", r) : "0")
                .con("Q", q).con("E", String.format(Locale.ROOT, "%.1f", pi.error()))
                .con("TARDE", String.format(Locale.ROOT, "%.1f", tarde)));
    }

    /** Pacing (§12.6): espera hasta que toque el siguiente binario. Sin REPORT en 300 ms, R = R_min. */
    private void esperarTurno() throws InterruptedException {
        long espera = proximoEnvio - System.nanoTime();
        if (espera > 0) {
            Thread.sleep(Duration.ofNanos(espera));
        }
    }

    private void programarSiguiente() {
        boolean sinReportes = ultimoReporte != 0 && System.nanoTime() - ultimoReporte > 300_000_000L;
        double r = sinReportes ? ControladorPI.R_MIN : tasa;
        proximoEnvio = System.nanoTime() + (long) (1e9 / r);
    }

    // ======================= Hilo emisor =======================

    private void bucleEmisor() {
        long seqContado = -1;
        int enviadosEnSeq = 0;
        int tardeEnSeq = 0;
        int paridadesEnSeq = 0;
        try {
            while (true) {
                lockCola.lock();
                try {
                    while (cola.estaVacia()) {
                        hayTrabajo.await();        // duerme sin gastar CPU hasta que haya trabajo
                    }
                } finally {
                    lockCola.unlock();
                }
                if (controlRitmo) {
                    esperarTurno();                // ANTES de sacar el pedido: la espera cuenta para TARDE
                }
                PlanificadorEDF.Turno<Pedido> turno;
                long tardesTotal, atendidosTotal;
                lockCola.lock();
                try {
                    if (cola.estaVacia()) {
                        continue;                  // la vista se canceló mientras esperaba su turno
                    }
                    turno = cola.extraer(System.nanoTime());   // el de plazo más próximo (§14.2)
                    tardesTotal = cola.tardes();
                    atendidosTotal = cola.atendidos();
                } finally {
                    lockCola.unlock();
                }
                Pedido p = turno.trabajo();

                if (p.seq() != seqContado) {
                    seqContado = p.seq();
                    enviadosEnSeq = 0;
                    tardeEnSeq = 0;
                    paridadesEnSeq = 0;
                }
                if (p.tipo() == Tipo.DONE) {
                    enviar(Mensaje.de("DONE").con("SEQ", p.seq()).con("SENT", enviadosEnSeq).con("PAR", paridadesEnSeq));
                    log(String.format("DONE seq=%d SENT=%d PAR=%d TARDE=%d | sesion: %d de %d tarde (%.1f %%)%s | %s",
                            p.seq(), enviadosEnSeq, paridadesEnSeq, tardeEnSeq, tardesTotal, atendidosTotal,
                            atendidosTotal == 0 ? 0.0 : 100.0 * tardesTotal / atendidosTotal,
                            enlace.simulado() ? ", perdidos en el enlace: " + enlace.descartados() : "",
                            cache.estadisticas()));
                } else if (p.tipo() == Tipo.PARIDAD) {
                    if (enviarParidad(p)) {
                        programarSiguiente();
                        paridadesEnSeq++;
                        if (turno.tarde()) {
                            tardeEnSeq++;
                        }
                    }
                } else if (enviarTile(p)) {
                    programarSiguiente();
                    enviadosEnSeq++;
                    if (turno.tarde()) {
                        tardeEnSeq++;
                    }
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
        enlace.enviarBinario(TileFrame.construir(p.seq(), ++num, p.z(), p.x(), p.y(), formato, datos));
        if (img == imagen) {
            long clave = new Vista.Tile(p.z(), p.x(), p.y()).clave();
            lockCola.lock();
            try {
                enviadosRecientes.remove(clave);   // un reenvío pasa al final, con su NUM nuevo
                enviadosRecientes.put(clave, num); // se marca AL ENVIAR (§13.4), no al encolar
            } finally {
                lockCola.unlock();
            }
        }
        return true;
    }

    /** PARIDAD (§11.4): el XOR se calcula al enviarla, con los datos de sus miembros desde la caché. */
    private boolean enviarParidad(Pedido p) throws IOException {
        Catalogo.Imagen img = p.img();
        byte formato = img.formato().equals("PNG") ? TileFrame.FMT_PNG : TileFrame.FMT_JPEG;
        List<TileFrame.Miembro> miembros = new ArrayList<>();
        List<byte[]> datos = new ArrayList<>();
        for (Vista.Tile t : p.grupo()) {
            try {
                byte[] d = cache.obtenerSinUso(img.id(),img.almacen(), t.z(), t.x(), t.y());
                miembros.add(new TileFrame.Miembro(t.z(), t.x(), t.y(), formato, d));
                datos.add(d);
            } catch (NoSuchFileException e) {
                // §11.4: un miembro que no se pudo leer se excluye de la paridad
            }
        }
        if (miembros.size() < 2) {
            return false;                          // con un solo miembro sería una copia
        }
        enlace.enviarBinario(TileFrame.construirParidad(p.seq(), ++num, miembros, FecXor.paridad(datos)));
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
        enlace.enviarTexto(m.toString());
    }

    private void log(String mensaje) {
        System.out.printf("[%s] %s%n", ws == null ? "?" : ws.cliente(), mensaje);
    }
}