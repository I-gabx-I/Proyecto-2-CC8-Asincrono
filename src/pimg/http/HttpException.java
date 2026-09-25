package pimg.http;

public final class HttpException extends Exception {
    private final int codigo;

    public HttpException(int codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }
}