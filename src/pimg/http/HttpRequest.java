package pimg.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class HttpRequest {
    private static final int MAX_LINEA = 8192;
    private static final int MAX_CABECERAS = 100;

    private final String metodo;
    private final String ruta;
    private final String version;
    private final Map<String, String> cabeceras;

    private HttpRequest(String metodo, String ruta, String version, Map<String, String> cabeceras) {
        this.metodo = metodo;
        this.ruta = ruta;
        this.version = version;
        this.cabeceras = cabeceras;
    }

    /** Lee una petición completa. Devuelve null si el cliente cerró la conexión. */
    public static HttpRequest leer(InputStream in) throws IOException, HttpException {
        // RFC 9112 §2.2: se pueden ignorar líneas vacías antes de la línea de petición
        String lineaInicial = leerLinea(in);
        while (lineaInicial != null && lineaInicial.isEmpty()) {
            lineaInicial = leerLinea(in);
        }
        if (lineaInicial == null) {
            return null;
        }

        // Línea de petición: METODO RUTA VERSION
        String[] partes = lineaInicial.split(" ", -1);
        if (partes.length != 3 || !partes[2].startsWith("HTTP/1.")) {
            throw new HttpException(400, "Línea de petición inválida: " + lineaInicial);
        }

        // Cabeceras hasta la línea vacía
        Map<String, String> cabeceras = new HashMap<>();
        while (true) {
            String linea = leerLinea(in);
            if (linea == null) {
                throw new HttpException(400, "Cabeceras incompletas");
            }
            if (linea.isEmpty()) {
                break;
            }
            if (cabeceras.size() >= MAX_CABECERAS) {
                throw new HttpException(400, "Demasiadas cabeceras");
            }
            int dosPuntos = linea.indexOf(':');
            if (dosPuntos <= 0) {
                throw new HttpException(400, "Cabecera inválida: " + linea);
            }
            // Los nombres de cabecera no distinguen mayúsculas (RFC 9110 §5.1)
            String nombre = linea.substring(0, dosPuntos).trim().toLowerCase(Locale.ROOT);
            String valor = linea.substring(dosPuntos + 1).trim();
            cabeceras.merge(nombre, valor, (a, b) -> a + ", " + b);
        }

        return new HttpRequest(partes[0], partes[1], partes[2], cabeceras);
    }

    /** Lee hasta \n y quita el \r final. Devuelve null si hay EOF sin datos. */
    private static String leerLinea(InputStream in) throws IOException, HttpException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            if (sb.length() >= MAX_LINEA) {
                throw new HttpException(400, "Línea demasiado larga");
            }
            sb.append((char) b); // ISO-8859-1: 1 byte = 1 carácter
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public String metodo()  { return metodo; }
    public String ruta()    { return ruta; }
    public String version() { return version; }

    public String cabecera(String nombre) {
        return cabeceras.get(nombre.toLowerCase(Locale.ROOT));
    }

    /** HTTP/1.1 mantiene la conexión por defecto; HTTP/1.0 la cierra por defecto. */
    public boolean keepAlive() {
        String c = cabecera("connection");
        String conn = c == null ? "" : c.toLowerCase(Locale.ROOT);
        if (version.equals("HTTP/1.0")) {
            return conn.contains("keep-alive");
        }
        return !conn.contains("close");
    }
}