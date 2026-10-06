package pimg.protocol;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32;

/** Mensaje binario TILE (PROTOCOLO.md §8.1): cabecera de 28 bytes big-endian + datos. */
public final class TileFrame {
    public static final int CABECERA = 28;
    public static final byte VERSION = 2;
    public static final byte TIPO_TILE = 1;
    public static final byte TIPO_PARIDAD = 2;
    public static final byte FMT_JPEG = 1;
    public static final byte FMT_PNG = 2;

    private TileFrame() {}

    /** Un tile cubierto por una paridad: coordenadas, formato y sus datos (para la longitud y el CRC). */
    public record Miembro(int z, int x, int y, byte formato, byte[] datos) {}

    public static byte[] construir(long seq, long num, int z, int x, int y, byte formato, byte[] datos) {
        CRC32 crc = new CRC32();
        crc.update(datos);

        ByteBuffer b = ByteBuffer.allocate(CABECERA + datos.length); // big-endian por defecto
        b.put(VERSION)                    // offset 0
         .put(TIPO_TILE)                  // 1
         .putInt((int) seq)               // 2..5   SEQ de la vista (uint32)
         .putInt((int) num)               // 6..9   NUM: número de envío (uint32, §8.3)
         .put((byte) z)                   // 10
         .putInt(x)                       // 11..14
         .putInt(y)                       // 15..18
         .put(formato)                    // 19
         .putInt(datos.length)            // 20..23
         .putInt((int) crc.getValue())    // 24..27
         .put(datos);                     // 28..
        return b.array();
    }

    /** Mensaje PARIDAD (§8.2): cabecera común + K entradas de 18 B + LONG_P + CRC_P + DATOS_P. */
    public static byte[] construirParidad(long seq, long num, List<Miembro> miembros, byte[] paridad) {
        ByteBuffer b = ByteBuffer.allocate(19 + 18 * miembros.size() + paridad.length);
        b.put(VERSION)                    // 0
         .put(TIPO_PARIDAD)               // 1
         .putInt((int) seq)               // 2..5
         .putInt((int) num)               // 6..9
         .put((byte) miembros.size());    // 10: K
        for (Miembro m : miembros) {      // 11 + 18·i: Z, X, Y, FMT, LONG_i, CRC_i
            b.put((byte) m.z()).putInt(m.x()).putInt(m.y()).put(m.formato())
             .putInt(m.datos().length).putInt(crc(m.datos()));
        }
        b.putInt(paridad.length)          // 11 + 18·K: LONG_P
         .putInt(crc(paridad))            // 15 + 18·K: CRC_P
         .put(paridad);                   // 19 + 18·K: DATOS_P
        return b.array();
    }

    private static int crc(byte[] datos) {
        CRC32 c = new CRC32();
        c.update(datos);
        return (int) c.getValue();
    }
}