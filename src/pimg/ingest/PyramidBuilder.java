package pimg.ingest;

import pimg.tiles.PyramidLayout;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;

/**
 * Construye TODOS los niveles de la pirámide en una sola pasada de lectura.
 * Memoria: cada nivel guarda como máximo una franja de su propio ancho
 * -> en total ≈ 2 franjas del nivel máximo (1 + 1/2 + 1/4 + ...).
 */
public final class PyramidBuilder {
    private final PyramidLayout piramide;
    private final TileEncoderPool compresor;
    private final int T;
    private final Nivel[] niveles;

    public PyramidBuilder(PyramidLayout piramide, TileEncoderPool compresor) {
        this.piramide = piramide;
        this.compresor = compresor;
        this.T = piramide.tile();
        this.niveles = new Nivel[piramide.niveles()];
        for (int z = 0; z < niveles.length; z++) {
            niveles[z] = new Nivel(z);
        }
    }

    /** Recibe la siguiente franja del nivel máximo (en orden, de arriba hacia abajo). */
    public void agregarFranjaOriginal(Franja f) {
        niveles[piramide.zMax()].emitir(f);
    }

    /** Al terminar de leer: vacía los niveles incompletos, del más detallado al menos detallado. */
    public void terminar() {
        for (int z = piramide.zMax() - 1; z >= 0; z--) {
            niveles[z].vaciar();
        }
    }

    private final class Nivel {
        final int z;
        final int ancho;
        final byte[] buffer;        // hasta T filas de este nivel (null en el nivel máximo)
        int filasEnBuffer = 0;
        int siguienteFilaTiles = 0; // índice y de la próxima fila de tiles

        Nivel(int z) {
            this.z = z;
            this.ancho = piramide.ancho(z);
            this.buffer = (z == piramide.zMax()) ? null : new byte[ancho * T * 3];
        }

        /** Agrega filas reducidas que llegan del nivel de abajo. */
        void agregar(Franja mitad) {
            int bytesFila = ancho * 3;
            int copiadas = 0;
            while (copiadas < mitad.alto()) {
                int n = Math.min(mitad.alto() - copiadas, T - filasEnBuffer);
                System.arraycopy(mitad.bgr(), copiadas * bytesFila,
                                 buffer, filasEnBuffer * bytesFila, n * bytesFila);
                filasEnBuffer += n;
                copiadas += n;
                if (filasEnBuffer == T) {
                    vaciar();
                }
            }
        }

        /** Emite lo acumulado como franja. Solo queda incompleta la última de cada nivel. */
        void vaciar() {
            if (filasEnBuffer == 0) {
                return;
            }
            // Si está llena se pasa el buffer sin copiar: emitir() es síncrono y no guarda la referencia
            byte[] datos = (filasEnBuffer == T) ? buffer : Arrays.copyOf(buffer, ancho * filasEnBuffer * 3);
            Franja f = new Franja(ancho, filasEnBuffer, datos);
            filasEnBuffer = 0;
            emitir(f);
        }

        /** Corta la franja en tiles y manda su reducción al nivel superior. */
        void emitir(Franja f) {
            cortarEnTiles(f);
            if (z > 0) {
                niveles[z - 1].agregar(Reductor.reducir(f));
            }
        }

        void cortarEnTiles(Franja f) {
            int y = siguienteFilaTiles++;
            int bytesFilaFranja = f.ancho() * 3;
            for (int x = 0; x < piramide.columnas(z); x++) {
                int x0 = x * T;
                int w = Math.min(T, f.ancho() - x0);   // último tile de la fila: más angosto
                BufferedImage tile = new BufferedImage(w, f.alto(), BufferedImage.TYPE_3BYTE_BGR);
                byte[] px = ((DataBufferByte) tile.getRaster().getDataBuffer()).getData();
                for (int fila = 0; fila < f.alto(); fila++) {
                    System.arraycopy(f.bgr(), fila * bytesFilaFranja + x0 * 3, px, fila * w * 3, w * 3);
                }
                compresor.enviar(z, x, y, tile);
            }
        }
    }
}