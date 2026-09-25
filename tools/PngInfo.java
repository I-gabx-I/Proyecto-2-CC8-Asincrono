import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Lee la cabecera de un PNG sin cargar los píxeles: dimensiones, formato y compresión. */
public class PngInfo {
    private static final byte[] FIRMA = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    public static void main(String[] args) throws IOException {
        Path ruta = Path.of(args[0]);
        try (FileChannel ch = FileChannel.open(ruta, StandardOpenOption.READ)) {
            long tamArchivo = ch.size();

            ByteBuffer firma = leer(ch, 0, 8);
            for (int i = 0; i < 8; i++) {
                if (firma.get(i) != FIRMA[i]) {
                    System.out.println("No es un PNG valido");
                    return;
                }
            }

            long ancho = 0, alto = 0;
            int bits = 0, tipoColor = 0, entrelazado = 0;
            long pos = 8;

            while (pos < tamArchivo) {
                ByteBuffer cab = leer(ch, pos, 8);
                long largo = Integer.toUnsignedLong(cab.getInt(0));
                String tipo = new String(new byte[]{cab.get(4), cab.get(5), cab.get(6), cab.get(7)},
                        StandardCharsets.US_ASCII);

                if (tipo.equals("IHDR")) {
                    ByteBuffer d = leer(ch, pos + 8, 13);
                    ancho = Integer.toUnsignedLong(d.getInt(0));
                    alto = Integer.toUnsignedLong(d.getInt(4));
                    bits = d.get(8) & 0xFF;
                    tipoColor = d.get(9) & 0xFF;
                    entrelazado = d.get(12) & 0xFF;
                } else if (tipo.equals("IDAT")) {
                    ByteBuffer z = leer(ch, pos + 8, 3);
                    int flg = z.get(1) & 0xFF;
                    int btype = ((z.get(2) & 0xFF) >> 1) & 3;
                    System.out.printf("Primer IDAT: %d bytes | nivel zlib (FLEVEL): %d | primer bloque deflate: %s%n",
                            largo, (flg >> 6) & 3,
                            btype == 0 ? "SIN COMPRESION (stored)" : btype == 1 ? "Huffman fijo" : "Huffman dinamico");
                    break;                         // no hace falta recorrer los 93 GB
                } else {
                    System.out.printf("Chunk %s: %d bytes%n", tipo, largo);
                }
                pos += 12 + largo;                 // 4 largo + 4 tipo + datos + 4 CRC
            }

            int canales = switch (tipoColor) {
                case 0 -> 1; case 2 -> 3; case 3 -> 1; case 4 -> 2; case 6 -> 4;
                default -> 0;
            };
            String nombreColor = switch (tipoColor) {
                case 0 -> "gris"; case 2 -> "RGB"; case 3 -> "paleta"; case 4 -> "gris+alfa"; case 6 -> "RGBA";
                default -> "desconocido";
            };
            long bytesCrudos = ancho * alto * canales * bits / 8;

            System.out.printf("%nDimensiones: %d x %d px (%.2f gigapixeles)%n", ancho, alto, ancho * alto / 1e9);
            System.out.printf("Color: %s | %d bits por canal | entrelazado: %s%n",
                    nombreColor, bits, entrelazado == 0 ? "no" : "SI (Adam7)");
            System.out.printf("Archivo: %.2f GB | pixeles sin comprimir: %.2f GB | relacion: %.2f%n",
                    tamArchivo / 1e9, bytesCrudos / 1e9, (double) tamArchivo / bytesCrudos);
        }
    }

    private static ByteBuffer leer(FileChannel ch, long pos, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) {
            if (ch.read(b, pos + b.position()) < 0) {
                throw new EOFException("Fin de archivo inesperado");
            }
        }
        return b;
    }
}