package pimg.protocol;

import pimg.transporte.RedSimulada;
import pimg.websocket.WebSocketConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Salida de UNA sesión hacia el socket (PROTOCOLO.md §16).
 * Sin --sim escribe directo. Con --sim, cada mensaje pasa por la red simulada: los binarios pueden
 * perderse; texto y binarios esperan su turno en una cola FIFO acotada y salen al ritmo del enlace.
 */
final class Enlace {
    private static final long CAPACIDAD = 4L * 1024 * 1024;   // §16: 4 MB en la cola del enlace

    /** Un mensaje en la cola: binario o texto, y el instante en que debe salir. */
    private record Salida(byte[] binario, String texto, int bytes, long saleEn) {}

    private final WebSocketConnection ws;
    private final RedSimulada red;                 // null sin --sim
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition cambio = lock.newCondition();
    private final ArrayDeque<Salida> cola = new ArrayDeque<>();
    private long bytesEnCola = 0;
    private long descartados = 0;
    private volatile boolean cerrado = false;
    private Thread hilo;

    Enlace(WebSocketConnection ws, boolean simulado) {
        this.ws = ws;
        this.red = simulado ? new RedSimulada() : null;
    }

    void iniciar() {
        if (red != null) {
            hilo = Thread.ofVirtual().start(this::bucle);
        }
    }

    void enviarTexto(String texto) throws IOException {
        if (red == null) {
            ws.enviarTexto(texto);
        } else {
            encolar(null, texto, texto.getBytes(StandardCharsets.UTF_8).length, false);
        }
    }

    void enviarBinario(byte[] datos) throws IOException {
        if (red == null) {
            ws.enviarBinario(datos);
        } else {
            encolar(datos, null, datos.length, true);   // D-39: solo los binarios pueden perderse
        }
    }

    private void encolar(byte[] binario, String texto, int bytes, boolean descartable) throws IOException {
        lock.lock();
        try {
            if (descartable && red.descartar()) {
                descartados++;                          // ya tiene NUM: el cliente verá el salto
                return;
            }
            if (red.inactiva() && cola.isEmpty()) {     // sin pérdida, límite ni latencia: directo
                escribir(binario, texto);
                return;
            }
            while (bytesEnCola > 0 && bytesEnCola + bytes > CAPACIDAD && !cerrado) {
                cambio.await();                         // cola llena: el emisor espera (contrapresión)
            }
            if (cerrado) {
                throw new IOException("Enlace cerrado");
            }
            cola.addLast(new Salida(binario, texto, bytes, red.salida(System.nanoTime(), bytes)));
            bytesEnCola += bytes;
            cambio.signalAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Enlace interrumpido");
        } finally {
            lock.unlock();
        }
    }

    /** Hilo del enlace: saca cada mensaje cuando llega su hora y lo escribe en el socket. */
    private void bucle() {
        try {
            while (true) {
                Salida s;
                lock.lock();
                try {
                    while (cola.isEmpty()) {
                        cambio.await();
                    }
                    s = cola.peekFirst();               // solo este hilo saca de la cola
                } finally {
                    lock.unlock();
                }
                long espera = s.saleEn() - System.nanoTime();
                if (espera > 0) {
                    Thread.sleep(Duration.ofNanos(espera));
                }
                escribir(s.binario(), s.texto());
                lock.lock();
                try {
                    cola.pollFirst();
                    bytesEnCola -= s.bytes();
                    cambio.signalAll();                 // despierta al emisor si esperaba espacio
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException | IOException e) {
            // la sesión terminó o el socket se cerró
        }
    }

    private void escribir(byte[] binario, String texto) throws IOException {
        if (binario != null) {
            ws.enviarBinario(binario);
        } else {
            ws.enviarTexto(texto);
        }
    }

    boolean simulado() {
        return red != null;
    }

    void configurar(int perdida, int anchoKBs, int latenciaMs) {
        lock.lock();
        try {
            red.configurar(perdida, anchoKBs, latenciaMs);
        } finally {
            lock.unlock();
        }
    }

    long descartados() {
        lock.lock();
        try {
            return descartados;
        } finally {
            lock.unlock();
        }
    }

    void cerrar() {
        cerrado = true;
        if (hilo != null) {
            hilo.interrupt();
        }
        lock.lock();
        try {
            cambio.signalAll();                         // libera a quien espere espacio
        } finally {
            lock.unlock();
        }
    }
}