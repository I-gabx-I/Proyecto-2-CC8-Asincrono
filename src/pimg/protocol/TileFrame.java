package pimg.protocol;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Mensaje binario TILE (PROTOCOLO.md §8.1): cabecera de 28 bytes big-endian + datos. */
public final class TileFrame {
    public static final int CABECERA = 28;
    public static final byte VERSION = 2;
    public static final byte TIPO_TILE = 1;
    public static final byte FMT_JPEG = 1;
    public static final byte FMT_PNG = 2;

    private TileFrame() {}

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
}