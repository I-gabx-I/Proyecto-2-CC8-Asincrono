package pimg;

import pimg.http.HttpServer;
import pimg.http.Router;
import pimg.http.StaticFileHandler;
import pimg.protocol.SesionPimg;
import pimg.tiles.Catalogo;
import pimg.tiles.TileCache;
import pimg.websocket.WebSocketHandler;

import java.nio.file.Path;

public class Main {
    private static final int HEARTBEAT_SEG = 15;              // PROTOCOLO.md §9
    private static final int MAX_MENSAJE = 16 * 1024;         // PROTOCOLO.md §5.1
    private static final int TAM_TILE = 256;
    private static final long CACHE_BYTES = 128L * 1024 * 1024; // 128 MB compartidos por todos

    public static void main(String[] args) throws Exception {
        int puerto = 8080;
        boolean redSimulada = false;
        boolean controlRitmo = true;
        boolean usarLru = false;
        for (String a : args) {
            if (a.equals("--sim")) {
                redSimulada = true;                           // PROTOCOLO.md §16
            } else if (a.equals("--lru")) {
                usarLru = true;                               // comparación: LRU en lugar de ARC (D-30)
            } else if (a.equals("--sin-pi")) {
                controlRitmo = false;                         // experimento: sin control de ritmo (§12)
            } else {
                puerto = Integer.parseInt(a);
            }
        }

        // Recursos COMPARTIDOS por todas las sesiones
        Catalogo catalogo = new Catalogo(Path.of("data", "tiles"));
        TileCache cache = new TileCache(CACHE_BYTES, usarLru);

        final boolean simulada = redSimulada;               // la lambda necesita variables finales
        final boolean conPI = controlRitmo;
        System.out.println("Red simulada: " + (simulada ? "ACTIVADA (--sim)" : "desactivada"));
        System.out.println("Control de ritmo PI: " + (conPI ? "activado" : "DESACTIVADO (--sin-pi)"));
        System.out.println("Cache del servidor: " + (usarLru ? "LRU (--lru, solo para comparar)" : "ARC"));

        // Una SesionPimg NUEVA por cada conexión WebSocket
        WebSocketHandler ws = new WebSocketHandler("pimg.v2",
                () -> new SesionPimg(catalogo, cache, HEARTBEAT_SEG, TAM_TILE, simulada, conPI),
                HEARTBEAT_SEG, MAX_MENSAJE);

        Router router = new Router(new StaticFileHandler(Path.of("web"))).ruta("/ws", ws);
        new HttpServer(puerto, router).iniciar();
    }
}