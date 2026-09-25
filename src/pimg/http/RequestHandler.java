package pimg.http;

import java.io.IOException;

@FunctionalInterface
public interface RequestHandler {
    /** @return true si la conexión puede quedar abierta para otra petición. */
    boolean manejar(HttpRequest peticion, Conexion conexion) throws IOException;
}