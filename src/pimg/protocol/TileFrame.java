package pimg.protocol;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Mensaje binario TILE (PROTOCOLO.md §6): cabecera de 24 bytes big-endian + datos. */
public final class TileFrame {
    public static final int CABECERA = 24;
    public static final byte VERSION = 1;
    public static final byte TIPO_TILE = 1;
    public static final byte FMT_JPEG = 1;
    public static final byte FMT_PNG = 2;

    private TileFrame() {}

    public static byte[] construir(long seq, int z, int x, int y, byte formato, byte[] datos) {
        CRC32 crc = new CRC32();
        crc.update(datos);

        ByteBuffer b = ByteBuffer.allocate(CABECERA + datos.length); // big-endian por defecto
        b.put(VERSION)                    // offset 0
         .put(TIPO_TILE)                  // 1
         .putInt((int) seq)               // 2..5   (uint32)
         .put((byte) z)                   // 6
         .putInt(x)                       // 7..10
         .putInt(y)                       // 11..14
         .put(formato)                    // 15
         .putInt(datos.length)            // 16..19
         .putInt((int) crc.getValue())    // 20..23
         .put(datos);                     // 24..
        return b.array();
    }
}