package pimg.http;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Elige el handler según la ruta exacta; si no hay ninguno, usa el de por defecto. */
public final class Router implements RequestHandler {
    private final Map<String, RequestHandler> rutas = new HashMap<>();
    private final RequestHandler porDefecto;

    public Router(RequestHandler porDefecto) {
        this.porDefecto = porDefecto;
    }

    public Router ruta(String ruta, RequestHandler handler) {
        rutas.put(ruta, handler);
        return this;
    }

    @Override
    public boolean manejar(HttpRequest req, Conexion con) throws IOException {
        String ruta = req.ruta();
        int q = ruta.indexOf('?');
        if (q >= 0) {
            ruta = ruta.substring(0, q);
        }
        return rutas.getOrDefault(ruta, porDefecto).manejar(req, con);
    }
}