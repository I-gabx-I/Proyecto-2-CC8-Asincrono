package pimg.websocket;

/** Listener de prueba: devuelve todo lo que recibe. Se reemplaza por la sesión PIMG en el paso C. */
public final class EchoListener implements WebSocketListener {

    @Override
    public void alAbrir(WebSocketConnection c) {
        log(c, "WebSocket abierto");
    }

    @Override
    public void alRecibirTexto(WebSocketConnection c, String texto) throws java.io.IOException {
        log(c, "texto de " + texto.length() + " caracteres");
        c.enviarTexto("eco: " + texto);
    }

    @Override
    public void alRecibirBinario(WebSocketConnection c, byte[] datos) throws java.io.IOException {
        log(c, "binario de " + datos.length + " bytes");
        c.enviarBinario(datos);
    }

    @Override
    public void alCerrar(WebSocketConnection c, int codigo) {
        log(c, "WebSocket cerrado con codigo " + codigo);
    }

    private static void log(WebSocketConnection c, String mensaje) {
        System.out.printf("[hilo %d] %s %s%n", Thread.currentThread().threadId(), c.cliente(), mensaje);
    }
}