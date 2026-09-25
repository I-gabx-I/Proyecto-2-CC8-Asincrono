package pimg.websocket;

import java.io.IOException;

/** Violación del protocolo WebSocket, con su código de cierre (RFC 6455 §7.4.1). */
public final class WebSocketException extends IOException {
    private final int codigo;

    public WebSocketException(int codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }
}