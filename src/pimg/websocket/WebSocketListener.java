package pimg.websocket;

import java.io.IOException;

/** Qué hacer con los mensajes. Hay UNA instancia por conexión: ahí vivirá la sesión PIMG. */
public interface WebSocketListener {
    void alAbrir(WebSocketConnection conexion) throws IOException;
    void alRecibirTexto(WebSocketConnection conexion, String texto) throws IOException;
    void alRecibirBinario(WebSocketConnection conexion, byte[] datos) throws IOException;
    void alCerrar(WebSocketConnection conexion, int codigo);
}