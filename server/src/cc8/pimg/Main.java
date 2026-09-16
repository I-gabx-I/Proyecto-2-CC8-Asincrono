package cc8.pimg;

import cc8.pimg.http.HttpServer;
import java.io.IOException;

public final class Main {
    public static void main(String[] args) throws IOException {
        System.out.println("PIMG - Servidor Asincrono de Imagenes");
        System.out.println("Java en ejecucion: " + Runtime.version());
        new HttpServer(8080).start();
    }
}