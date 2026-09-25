package pimg.ingest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Fuente basada en el ImageIO del JDK: TIFF, PNG, JPEG, BMP, GIF. */
public final class ImageIOSource implements ImageSource {
    private final ImageInputStream entrada;
    private final ImageReader lector;
    private final int ancho;
    private final int alto;

    // Diagnóstico: en qué se va el tiempo
    private long nanosLectura = 0;
    private long nanosConversion = 0;
    private String metodoConversion = "ninguna";

    public ImageIOSource(Path archivo) throws IOException {
        if (!Files.isRegularFile(archivo)) {
            throw new IOException("No existe el archivo: " + archivo.toAbsolutePath());
        }
        entrada = ImageIO.createImageInputStream(archivo.toFile());
        if (entrada == null) {
            throw new IOException("No se pudo abrir " + archivo);
        }
        Iterator<ImageReader> lectores = ImageIO.getImageReaders(entrada);
        if (!lectores.hasNext()) {
            entrada.close();
            throw new IOException("Formato no soportado por ImageIO: " + archivo);
        }
        lector = lectores.next();
        lector.setInput(entrada, true, true); // solo lee la cabecera, no los píxeles
        ancho = lector.getWidth(0);
        alto = lector.getHeight(0);
    }

    @Override public int ancho() { return ancho; }
    @Override public int alto()  { return alto; }

    public String descripcion() throws IOException {
        return lector.getFormatName() + " (" + lector.getClass().getSimpleName() + ")";
    }

    public double segundosLectura()    { return nanosLectura / 1e9; }
    public double segundosConversion() { return nanosConversion / 1e9; }
    public String metodoConversion()   { return metodoConversion; }

    @Override
    public Franja leerFranja(int y, int filas) throws IOException {
        ImageReadParam param = lector.getDefaultReadParam();
        param.setSourceRegion(new Rectangle(0, y, ancho, filas));

        long t0 = System.nanoTime();
        BufferedImage img = lector.read(0, param);
        long t1 = System.nanoTime();
        byte[] bgr = aBgr(img);
        long t2 = System.nanoTime();

        nanosLectura += t1 - t0;
        nanosConversion += t2 - t1;
        return new Franja(img.getWidth(), img.getHeight(), bgr);
    }

    /** Lleva cualquier imagen al formato interno BGR, usando el camino más rápido posible. */
    private byte[] aBgr(BufferedImage img) {
        // Caso 1: ya viene en BGR -> se usa tal cual, sin copiar
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            metodoConversion = "ninguna";
            return ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        }
        // Caso 2: bytes RGB en cualquier distribución -> reordenar a mano
        byte[] reordenado = reordenar(img);
        if (reordenado != null) {
            metodoConversion = "reordenar bytes";
            return reordenado;
        }
        // Caso 3: formato raro -> drawImage (general, pero lento)
        metodoConversion = "drawImage (lento)";
        BufferedImage bgr = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bgr.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
    }

    /**
     * Copia R, G, B a un arreglo nuevo en orden B, G, R.
     * Sirve para canales intercalados (RGBRGB...) y por planos (RRR...GGG...BBB...).
     * Devuelve null si la imagen no es de bytes RGB (p. ej. 16 bits), para usar el caso 3.
     */
    private static byte[] reordenar(BufferedImage img) {
        Raster raster = img.getRaster();
        if (!(raster.getDataBuffer() instanceof DataBufferByte datos)) return null;
        if (!(raster.getSampleModel() instanceof ComponentSampleModel modelo)) return null;
        if (modelo.getNumBands() < 3) return null;
        if (raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0) return null;
        if (img.getColorModel().getColorSpace().getType() != ColorSpace.TYPE_RGB) return null;

        int w = img.getWidth();
        int h = img.getHeight();
        int[] bancos = modelo.getBankIndices();       // en qué arreglo está cada canal
        int[] desplCanal = modelo.getBandOffsets();   // desplazamiento de cada canal dentro del píxel
        int[] desplBanco = datos.getOffsets();

        byte[] rojo  = datos.getData(bancos[0]);
        byte[] verde = datos.getData(bancos[1]);
        byte[] azul  = datos.getData(bancos[2]);
        int oR = desplBanco[bancos[0]] + desplCanal[0];
        int oG = desplBanco[bancos[1]] + desplCanal[1];
        int oB = desplBanco[bancos[2]] + desplCanal[2];
        int pasoPixel = modelo.getPixelStride();      // 3 si es intercalado, 1 si es por planos
        int pasoLinea = modelo.getScanlineStride();

        byte[] bgr = new byte[w * h * 3];
        int i = 0;
        for (int y = 0; y < h; y++) {
            int base = y * pasoLinea;
            for (int x = 0; x < w; x++) {
                int p = base + x * pasoPixel;
                bgr[i++] = azul[oB + p];
                bgr[i++] = verde[oG + p];
                bgr[i++] = rojo[oR + p];
            }
        }
        return bgr;
    }

    @Override
    public void close() throws IOException {
        lector.dispose();
        entrada.close();
    }
}