package pimg.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpServer {
    private static final int TIMEOUT_INACTIVO_MS = 30_000;

    private final int puerto;
    private final RequestHandler handler;

    public HttpServer(int puerto, RequestHandler handler) {
        this.puerto = puerto;
        this.handler = handler;
    }

    public void iniciar() throws IOException {
        try (ServerSocket servidor = new ServerSocket(puerto);
             ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            System.out.println("Servidor escuchando en http://localhost:" + puerto);
            while (true) {
                Socket socket = servidor.accept();
                hilos.submit(() -> atender(socket));
            }
        }
    }

    private void atender(Socket socket) {
        String cliente = socket.getRemoteSocketAddress().toString();
        try (socket) {
            socket.setSoTimeout(TIMEOUT_INACTIVO_MS);
            Conexion con = new Conexion(socket,
                    new BufferedInputStream(socket.getInputStream()),
                    new BufferedOutputStream(socket.getOutputStream()));

            boolean seguir = true;
            while (seguir) {                       // keep-alive: varias peticiones por conexión
                HttpRequest req;
                try {
                    req = HttpRequest.leer(con.entrada());
                } catch (HttpException e) {
                    log(cliente, "400 " + e.getMessage());
                    HttpResponse.enviarError(con.salida(), e.codigo(), false);
                    break;
                }
                if (req == null) {
                    break;                         // el cliente cerró
                }
                log(cliente, req.metodo() + " " + req.ruta());
                seguir = handler.manejar(req, con) && req.keepAlive();
            }
        } catch (SocketTimeoutException e) {
            // conexión keep-alive inactiva: cierre normal
        } catch (IOException e) {
            log(cliente, "conexión terminada: " + e.getMessage());
        } catch (RuntimeException e) {
            log(cliente, "error interno: " + e);
        }
    }

    private static void log(String cliente, String mensaje) {
        Thread t = Thread.currentThread();
        System.out.printf("[hilo %d %s] %s %s%n",
                t.threadId(), t.isVirtual() ? "virtual" : "plataforma", cliente, mensaje);
    }
}