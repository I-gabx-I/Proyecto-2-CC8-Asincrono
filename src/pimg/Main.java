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
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        // Recursos COMPARTIDOS por todas las sesiones
        Catalogo catalogo = new Catalogo(Path.of("data", "tiles"));
        TileCache cache = new TileCache(CACHE_BYTES);

        // Una SesionPimg NUEVA por cada conexión WebSocket
        WebSocketHandler ws = new WebSocketHandler("pimg.v1",
                () -> new SesionPimg(catalogo, cache, HEARTBEAT_SEG, TAM_TILE),
                HEARTBEAT_SEG, MAX_MENSAJE);

        Router router = new Router(new StaticFileHandler(Path.of("web"))).ruta("/ws", ws);
        new HttpServer(puerto, router).iniciar();
    }
}