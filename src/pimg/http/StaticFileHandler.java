package pimg.http;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class StaticFileHandler implements RequestHandler {
    private static final Map<String, String> TIPOS = Map.of(
            "html", "text/html; charset=utf-8",
            "css",  "text/css; charset=utf-8",
            "js",   "text/javascript; charset=utf-8",
            "json", "application/json",
            "png",  "image/png",
            "jpg",  "image/jpeg",
            "ico",  "image/x-icon");

    private final Path raiz;

    public StaticFileHandler(Path raiz) {
        this.raiz = raiz.toAbsolutePath().normalize();
    }

    @Override
    public boolean manejar(HttpRequest req, Conexion con) throws IOException {
        if (!req.metodo().equals("GET")) {
            HttpResponse.enviarError(con.salida(), 405, false);
            return false; // no leímos un posible cuerpo: más seguro cerrar
        }
        Path archivo = resolver(req.ruta());
        if (archivo == null) {
            HttpResponse.enviarError(con.salida(), 404, req.keepAlive());
            return true;
        }
        HttpResponse.enviarArchivo(con.salida(), archivo, tipoDe(archivo), req.keepAlive());
        return true;
    }

    /** Convierte la ruta de la URL en un archivo dentro de raiz, o null si no es válido. */
    private Path resolver(String ruta) {
        int q = ruta.indexOf('?');
        if (q >= 0) {
            ruta = ruta.substring(0, q);
        }
        if (!ruta.startsWith("/")) {
            return null;
        }
        if (ruta.equals("/")) {
            ruta = "/index.html";
        }
        try {
            String decodificada = URLDecoder.decode(ruta, StandardCharsets.UTF_8);
            Path p = raiz.resolve(decodificada.substring(1)).normalize();
            if (!p.startsWith(raiz)) {
                return null; // intento de salir de web/
            }
            return Files.isRegularFile(p) ? p : null;
        } catch (IllegalArgumentException e) { // % mal formado o ruta inválida
            return null;
        }
    }

    private static String tipoDe(Path archivo) {
        String nombre = archivo.getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        String ext = punto < 0 ? "" : nombre.substring(punto + 1).toLowerCase(Locale.ROOT);
        return TIPOS.getOrDefault(ext, "application/octet-stream");
    }
}