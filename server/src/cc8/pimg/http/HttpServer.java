package cc8.pimg.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpServer {
    private static final int READ_TIMEOUT_MS = 10_000;   // NUEVO

    private final int port;

    public HttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        try (ServerSocket server = new ServerSocket(port);
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {   // NUEVO
            System.out.println("Escuchando en http://localhost:" + port);
            while (true) {
                Socket client = server.accept();          // el hilo main solo acepta
                workers.submit(() -> handle(client));     // CAMBIO: cada cliente en su hilo virtual
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(READ_TIMEOUT_MS);                                   // NUEVO
            System.out.println("\nConexion desde " + client.getRemoteSocketAddress()
                    + " en " + Thread.currentThread());                             // CAMBIO

            // 1. Leer la peticion: lineas de texto hasta una linea vacia
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                System.out.println("  > " + line);
            }

            // 2. Responder: linea de estado + cabeceras + linea vacia + cuerpo
            byte[] body = "<h1>PIMG funcionando</h1>".getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";

            OutputStream out = client.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (SocketTimeoutException e) {                                        // NUEVO
            System.out.println("Timeout: cliente " + client.getRemoteSocketAddress() + " no envio datos");
        } catch (IOException e) {
            System.out.println("Error con el cliente: " + e.getMessage());
        }
    }
}