package pimg.ingest;

import java.io.IOException;

/** Cualquier fuente de imagen que pueda entregar franjas de filas completas. */
public interface ImageSource extends AutoCloseable {
    int ancho();
    int alto();

    /** Lee las filas [y, y + filas) con todo el ancho de la imagen. */
    Franja leerFranja(int y, int filas) throws IOException;

    @Override
    void close() throws IOException;
}