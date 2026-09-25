package pimg.protocol;

/** Error del protocolo PIMG con su código (PROTOCOLO.md §10). */
public final class PimgException extends Exception {
    public static final int MALFORMED           = 400;
    public static final int IMAGE_NOT_FOUND     = 404;
    public static final int IMAGE_NOT_READY     = 409;
    public static final int INVALID_STATE       = 412;
    public static final int OUT_OF_RANGE        = 416;
    public static final int VERSION_UNSUPPORTED = 426;
    public static final int INTERNAL            = 500;
    public static final int BUSY                = 503;

    private final int codigo;

    public PimgException(int codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }
}