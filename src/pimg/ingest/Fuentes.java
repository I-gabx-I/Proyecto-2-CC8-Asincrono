package pimg.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Elige el lector según la FIRMA del archivo (sus primeros bytes), no según la extensión.
 *   PNG                  -> PngSource (propio, en streaming)
 *   TIFF, JPEG, BMP      -> ImageIOSource (lector del JDK)
 *   cualquier otro       -> IOException "formato no soportado"
 */
public final class Fuentes {
    private Fuentes() {}

    public static ImageSource abrir(Path ruta) throws IOException {
        if (!Files.isRegularFile(ruta)) {
            throw new IOException("No existe el archivo: " + ruta.toAbsolutePath());
        }
        byte[] cabecera;
        try (InputStream in = Files.newInputStream(ruta)) {
            cabecera = in.readNBytes(8);
        }
        if (Arrays.equals(cabecera, PngSource.FIRMA)) {
            return new PngSource(ruta);
        }
        return new ImageIOSource(ruta);   // lanza IOException si ImageIO tampoco lo reconoce
    }
}