package pimg.ingest;

import java.io.IOException;

/**
 * Cualquier fuente de imagen que pueda entregar franjas de filas completas, en orden.
 * Agregar un formato = escribir una clase que implemente esta interfaz y registrarla en Fuentes.
 */
public interface ImageSource extends AutoCloseable {
    int ancho();
    int alto();

    /**
     * Lee las filas [y, y + filas) con todo el ancho de la imagen.
     * La ingesta siempre pide las franjas en orden; una fuente PUEDE exigirlo (PNG no puede retroceder).
     */
    Franja leerFranja(int y, int filas) throws IOException;

    /** Nombre del lector, para los mensajes de la ingesta. */
    default String descripcion() {
        return getClass().getSimpleName();
    }

    /** Tiempo acumulado leyendo y decodificando (diagnóstico). */
    default double segundosLectura() {
        return 0;
    }

    /** Tiempo acumulado convirtiendo al formato interno BGR (diagnóstico). */
    default double segundosConversion() {
        return 0;
    }

    @Override
    void close() throws IOException;
}