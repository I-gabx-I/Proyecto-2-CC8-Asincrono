package pimg.http;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public record Conexion(Socket socket, InputStream entrada, OutputStream salida) {}