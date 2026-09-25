package pimg.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HttpResponse {
    private HttpResponse() {}

    public static void enviarArchivo(OutputStream out, Path archivo, String tipo, boolean keepAlive)
            throws IOException {
        escribirCabecera(out, 200, tipo, Files.size(archivo), keepAlive, null);
        Files.copy(archivo, out);
        out.flush();
    }

    public static void enviarError(OutputStream out, int codigo, boolean keepAlive) throws IOException {
        enviarError(out, codigo, keepAlive, null);
    }

    /** cabeceraExtra: una línea "Nombre: valor" adicional, o null. */
    public static void enviarError(OutputStream out, int codigo, boolean keepAlive, String cabeceraExtra)
            throws IOException {
        byte[] cuerpo = (codigo + " " + razon(codigo) + "\n").getBytes(StandardCharsets.UTF_8);
        escribirCabecera(out, codigo, "text/plain; charset=utf-8", cuerpo.length, keepAlive, cabeceraExtra);
        out.write(cuerpo);
        out.flush();
    }

    private static void escribirCabecera(OutputStream out, int codigo, String tipo, long longitud,
                                         boolean keepAlive, String cabeceraExtra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(codigo).append(' ').append(razon(codigo)).append("\r\n");
        sb.append("Content-Type: ").append(tipo).append("\r\n");
        sb.append("Content-Length: ").append(longitud).append("\r\n");
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        if (codigo == 405) {
            sb.append("Allow: GET\r\n"); // RFC 9110 §15.5.6
        }
        if (cabeceraExtra != null) {
            sb.append(cabeceraExtra).append("\r\n");
        }
        sb.append("Server: PIMG-demo\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    public static String razon(int codigo) {
        return switch (codigo) {
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 426 -> "Upgrade Required";
            case 500 -> "Internal Server Error";
            default  -> "Unknown";
        };
    }
}