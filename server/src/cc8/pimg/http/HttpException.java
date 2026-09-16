package cc8.pimg.http;

/**
 * Error de una peticion que se responde con un codigo de estado HTTP.
 * Ej: 400 Bad Request, 431 Request Header Fields Too Large.
 */
public final class HttpException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String reason;

    public HttpException(int status, String reason, String detail) {
        super(detail);
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}