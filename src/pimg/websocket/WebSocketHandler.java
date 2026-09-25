package pimg.websocket;

import pimg.http.Conexion;
import pimg.http.HttpRequest;
import pimg.http.HttpResponse;
import pimg.http.RequestHandler;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.Supplier;

/** Handshake de apertura (RFC 6455 §4.2) y ciclo de vida de cada conexión WebSocket. */
public final class WebSocketHandler implements RequestHandler {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"; // RFC 6455 §1.3

    private final String subprotocolo;
    private final Supplier<WebSocketListener> fabricaSesiones;
    private final int heartbeatSeg;
    private final int maxMensaje;

    public WebSocketHandler(String subprotocolo, Supplier<WebSocketListener> fabricaSesiones,
                            int heartbeatSeg, int maxMensaje) {
        this.subprotocolo = subprotocolo;
        this.fabricaSesiones = fabricaSesiones;
        this.heartbeatSeg = heartbeatSeg;
        this.maxMensaje = maxMensaje;
    }

    @Override
    public boolean manejar(HttpRequest req, Conexion con) throws IOException {
        // 1. Validar la petición de Upgrade
        if (!req.metodo().equals("GET")
                || !contieneToken(req.cabecera("upgrade"), "websocket")
                || !contieneToken(req.cabecera("connection"), "upgrade")) {
            HttpResponse.enviarError(con.salida(), 400, false);
            return false;
        }
        if (!"13".equals(req.cabecera("sec-websocket-version"))) {
            HttpResponse.enviarError(con.salida(), 426, false, "Sec-WebSocket-Version: 13");
            return false;
        }
        String clave = req.cabecera("sec-websocket-key");
        if (!claveValida(clave) || !contieneToken(req.cabecera("sec-websocket-protocol"), subprotocolo)) {
            HttpResponse.enviarError(con.salida(), 400, false); // PROTOCOLO.md §3: sin pimg.v1 -> 400
            return false;
        }

        // 2. Responder 101: desde aquí la misma conexión TCP habla WebSocket
        String respuesta = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + calcularAccept(clave) + "\r\n"
                + "Sec-WebSocket-Protocol: " + subprotocolo + "\r\n"
                + "\r\n";
        con.salida().write(respuesta.getBytes(StandardCharsets.US_ASCII));
        con.salida().flush();

        // 3. Ciclo de vida de la sesión
        con.socket().setSoTimeout(2 * heartbeatSeg * 1000); // sin frames en 2×HB -> conexión muerta
        WebSocketConnection ws = new WebSocketConnection(con, maxMensaje);
        WebSocketListener sesion = fabricaSesiones.get();    // una sesión NUEVA por conexión
        Thread latido = Thread.ofVirtual().start(() -> latir(ws));

        int codigo = 1006; // "cierre anormal": no hubo intercambio de CLOSE
        try {
            sesion.alAbrir(ws);
            codigo = ws.atender(sesion);
        } catch (WebSocketException e) {
            codigo = e.codigo();
            cerrarSinFallar(ws, codigo, e.getMessage());
        } catch (SocketTimeoutException e) {
            codigo = 1006; // zombie: ni frames ni PONG en 2×HB
        } catch (IOException e) {
            codigo = 1006; // el cliente desapareció
        } catch (RuntimeException e) {
            codigo = 1011;
            cerrarSinFallar(ws, 1011, "Error interno");
        } finally {
            latido.interrupt();
            sesion.alCerrar(ws, codigo);
        }
        return false; // esta conexión TCP ya no vuelve a HTTP
    }

    private void latir(WebSocketConnection ws) {
        try {
            while (true) {
                Thread.sleep(heartbeatSeg * 1000L);
                ws.enviarPing();
            }
        } catch (InterruptedException | IOException e) {
            // la conexión terminó
        }
    }

    private static void cerrarSinFallar(WebSocketConnection ws, int codigo, String motivo) {
        try {
            ws.cerrar(codigo, motivo);
        } catch (IOException ignorada) {
            // el socket ya estaba roto
        }
    }

    /** ¿La lista separada por comas contiene el token? (ej. Connection: keep-alive, Upgrade) */
    private static boolean contieneToken(String lista, String token) {
        if (lista == null) {
            return false;
        }
        for (String parte : lista.split(",")) {
            if (parte.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /** La clave debe ser Base64 de 16 bytes aleatorios (RFC 6455 §4.1). */
    private static boolean claveValida(String clave) {
        if (clave == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(clave.trim()).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String calcularAccept(String clave) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); // RFC 3174
            byte[] hash = sha1.digest((clave.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);         // RFC 4648
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 no disponible", e);
        }
    }
}