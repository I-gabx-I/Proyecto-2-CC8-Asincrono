package pimg.ingest;

import pimg.tiles.Catalogo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Ingesta automática (PROTOCOLO.md §22.4): vigila data/entrada con WatchService y construye la pirámide de
 * cada imagen nueva en un PROCESO aparte (java -Xmx4g ... IngestMain), de una en una.
 *
 * Proceso aparte y no un hilo del servidor: la ingesta de 93 GB necesita 4 GB de heap (D-37) y el servidor
 * corre con 512 MB; además, si la ingesta falla (formato no soportado, memoria, disco lleno), el servidor
 * sigue atendiendo a los clientes.
 */
public final class IngestaAutomatica {
    private static final long QUIETO_MS = 5_000;        // 5 s sin eventos del archivo: la copia terminó
    private static final String MEMORIA = "-Xmx4g";     // igual que ingest.bat (D-37)
    private static final Pattern FRANJA = Pattern.compile("Franja\\s+(\\d+)/(\\d+)");

    private final Path entrada;
    private final Path tiles;
    private final Catalogo catalogo;
    private final BlockingQueue<Path> pendientes = new LinkedBlockingQueue<>();
    private final Map<Path, Long> copiando = new HashMap<>();   // archivo -> último evento (solo el hilo vigilante)
    // Huella (tamaño/fecha) de la versión de cada archivo que ya tiene pirámide o se está ingestando.
    // Un evento que no cambia la huella (antivirus, indexador, atributos) no provoca una reingesta.
    private final Map<Path, String> huellas = new ConcurrentHashMap<>();
    private volatile Process actual;
    private volatile Path enCurso;

    public IngestaAutomatica(Path entrada, Path tiles, Catalogo catalogo) {
        this.entrada = entrada;
        this.tiles = tiles;
        this.catalogo = catalogo;
    }

    public void iniciar() throws IOException {
        Files.createDirectories(entrada);
        WatchService servicio = entrada.getFileSystem().newWatchService();
        entrada.register(servicio, ENTRY_CREATE, ENTRY_MODIFY);    // antes de revisar: no queda un hueco sin vigilar

        // Lo que se copió con el servidor apagado y no tiene pirámide pasa por la misma espera que un archivo
        // nuevo (puede estar copiándose todavía). Lo que ya tiene pirámide solo se anota como visto.
        int alArrancar = 0;
        try (DirectoryStream<Path> archivos = Files.newDirectoryStream(entrada)) {
            for (Path p : archivos) {
                if (!esCandidato(p)) {
                    continue;
                }
                if (tienePiramide(p)) {
                    huellas.put(p, huella(p));
                } else {
                    catalogo.empezarIngesta(idDe(p));
                    copiando.put(p, 0L);               // antes de start(): el hilo vigilante lo ve (happens-before)
                    alArrancar++;
                }
            }
        }
        Thread.ofPlatform().daemon().name("ingesta-vigilante").start(() -> vigilar(servicio));
        Thread.ofPlatform().daemon().name("ingesta-trabajador").start(this::trabajar);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {       // Ctrl+C: no dejar una ingesta huérfana
            Process p = actual;
            if (p != null) {
                p.destroy();
            }
        }));
        System.out.println("Ingesta automatica: vigilando " + entrada + " (" + alArrancar + " pendientes al arrancar)");
    }

    /**
     * Hilo vigilante. Una copia grande genera eventos durante minutos; el archivo se encola cuando lleva
     * QUIETO_MS sin eventos, ningún otro programa lo tiene abierto para escribir y su huella cambió.
     */
    private void vigilar(WatchService servicio) {
        try {
            while (true) {
                try {
                    revisarEventos(servicio.poll(1, TimeUnit.SECONDS));
                    revisarCopias();
                } catch (RuntimeException e) {
                    System.out.println("[ingesta] error en el vigilante (sigue vigilando): " + e);
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // el servidor se detiene
        }
    }

    private void revisarEventos(WatchKey clave) {
        if (clave == null) {
            return;
        }
        for (WatchEvent<?> e : clave.pollEvents()) {
            if (e.kind() == OVERFLOW) {
                continue;                  // eventos perdidos: el próximo evento de ese archivo lo vuelve a anotar
            }
            Path p = entrada.resolve((Path) e.context());
            if (!esCandidato(p)) {
                continue;
            }
            if (!copiando.containsKey(p) && !tienePiramide(p)) {
                catalogo.empezarIngesta(idDe(p));         // imagen nueva: ya aparece en LIST como PROCESSING 0 %
                System.out.println("[ingesta] detectado " + p.getFileName() + ": esperando a que termine la copia");
            }
            copiando.put(p, System.currentTimeMillis());
        }
        clave.reset();
    }

    private void revisarCopias() {
        long ahora = System.currentTimeMillis();
        for (Iterator<Map.Entry<Path, Long>> it = copiando.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, Long> e = it.next();
            if (ahora - e.getValue() < QUIETO_MS) {
                continue;
            }
            Path p = e.getKey();
            if (!Files.isRegularFile(p)) {
                it.remove();                              // se borró o se movió antes de terminar
                if (!p.equals(enCurso)) {
                    catalogo.cancelarIngesta(idDe(p));    // vuelve a lo que haya en disco
                }
            } else if (!nadieLoEscribe(p)) {
                e.setValue(ahora);                        // otro programa lo sigue escribiendo
            } else {
                it.remove();
                if (huella(p).equals(huellas.get(p))) {
                    continue;                             // el contenido no cambió: no se reingesta
                }
                encolar(p);
            }
        }
    }

    /** Hilo trabajador: una ingesta a la vez (cada una ya usa todos los núcleos y hasta 4 GB). */
    private void trabajar() {
        while (true) {
            Path p;
            try {
                p = pendientes.take();
            } catch (InterruptedException e) {
                return;
            }
            String id = idDe(p);
            long inicio = System.nanoTime();
            boolean ok = false;
            enCurso = p;
            try {
                huellas.put(p, huella(p));               // la versión que se va a ingestar
                catalogo.empezarIngesta(id);
                ok = ejecutar(p, id) == 0 && Files.isRegularFile(tiles.resolve(id).resolve("meta.json"));
            } catch (IOException | RuntimeException e) {
                System.out.println("[ingesta " + id + "] error: " + e);
            } catch (InterruptedException e) {
                return;
            } finally {
                enCurso = null;
            }
            if (!ok) {
                huellas.remove(p);                        // si se vuelve a copiar el mismo archivo, se reintenta
            }
            catalogo.terminarIngesta(id, ok);
            System.out.printf("[ingesta %s] %s en %.0f s%n", id, ok ? "READY" : "FAILED", (System.nanoTime() - inicio) / 1e9);
        }
    }

    /** Corre IngestMain con la misma JVM y el mismo classpath del servidor; el % sale de sus líneas "Franja i/n". */
    private int ejecutar(Path archivo, String id) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process proceso = new ProcessBuilder(java, MEMORIA, "-cp", System.getProperty("java.class.path"),
                "pimg.ingest.IngestMain", archivo.toString(), id)
                .redirectErrorStream(true)
                .start();
        actual = proceso;
        try {
            try (BufferedReader salida = new BufferedReader(new InputStreamReader(proceso.getInputStream()))) {
                String linea;
                while ((linea = salida.readLine()) != null) {
                    System.out.println("[ingesta " + id + "] " + linea);
                    Matcher m = FRANJA.matcher(linea);
                    if (m.find()) {        // 99 como máximo: falta escribir los índices y meta.json
                        long hechas = Long.parseLong(m.group(1)), total = Long.parseLong(m.group(2));
                        catalogo.progreso(id, (int) Math.min(99, 100 * hechas / total));
                    }
                }
            }
            return proceso.waitFor();
        } finally {
            if (proceso.isAlive()) {
                proceso.destroy();         // falló la lectura o se interrumpió: no dejar el proceso huérfano
            }
            actual = null;
        }
    }

    private void encolar(Path p) {
        catalogo.empezarIngesta(idDe(p));
        if (!pendientes.contains(p)) {
            pendientes.add(p);
        }
    }

    private boolean tienePiramide(Path p) {
        return Files.isRegularFile(tiles.resolve(idDe(p)).resolve("meta.json"));
    }

    private static String huella(Path p) {
        try {
            return Files.size(p) + "/" + Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return "";
        }
    }

    /** Si otro programa todavía escribe el archivo, Windows no deja abrirlo para escritura (archivo en uso). */
    private static boolean nadieLoEscribe(Path p) {
        try (FileChannel c = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            return true;                   // solo se abre: no se escribe nada
        } catch (AccessDeniedException e) {
            return true;                   // archivo de solo lectura: tampoco lo escribe nadie
        } catch (IOException e) {
            return false;
        }
    }

    /** El formato se detecta después por la firma (§22.3); aquí solo se descartan archivos que no son imágenes. */
    private static boolean esCandidato(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return !Files.isDirectory(p) && !n.startsWith(".") && !n.equals("desktop.ini") && !n.equals("thumbs.db")
                && !n.endsWith(".tmp") && !n.endsWith(".part") && !n.endsWith(".crdownload");
    }

    /**
     * El id es el nombre del archivo sin extensión, con los caracteres no permitidos (§5.1) cambiados por '_'.
     * Dos archivos con el mismo nombre base (a.png y a.tif) comparten id: el último que se ingesta gana.
     */
    static String idDe(Path p) {
        String n = p.getFileName().toString();
        int punto = n.lastIndexOf('.');
        if (punto > 0) {
            n = n.substring(0, punto);
        }
        n = n.replaceAll("[^A-Za-z0-9_-]", "_");
        if (n.length() > 64) {
            n = n.substring(0, 64);
        }
        return n.isEmpty() ? "imagen" : n;
    }
}