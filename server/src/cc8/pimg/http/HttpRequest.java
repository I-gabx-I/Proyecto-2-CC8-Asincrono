package cc8.pimg.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Peticion HTTP/1.1 ya interpretada (RFC 9112 seccion 3).
 * Los nombres de cabecera se guardan en minuscula porque HTTP
 * no distingue mayusculas en ellos (RFC 9110 seccion 5.1).
 */
public record HttpRequest(String method, String target, String version, Map<String, String> headers) {

    public HttpRequest {
        Map<String, String> normalized = new LinkedHashMap<>();
        headers.forEach((name, value) -> normalized.put(name.toLowerCase(Locale.ROOT), value));
        headers = Collections.unmodifiableMap(normalized);
    }

    /** Valor de una cabecera sin importar mayusculas, o null si no existe. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Indica si una cabecera con lista de valores contiene un token.
     * Ej: "Connection: keep-alive, Upgrade" contiene "upgrade".
     */
    public boolean hasToken(String name, String token) {
        String value = header(name);
        if (value == null) {
            return false;
        }
        for (String part : value.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /** Ruta sin la query string: "/index.html?x=1" -> "/index.html". */
    public String path() {
        int q = target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }

    /** Resumen para el log, sin cabeceras (evita registrar cookies). */
    public String summary() {
        return method + " " + target;
    }
}