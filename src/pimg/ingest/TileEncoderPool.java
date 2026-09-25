package pimg.ingest;

import pimg.tiles.TileStore;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Comprime tiles a JPEG y los guarda, en paralelo. Trabajo de CPU: hilos de plataforma. */
public final class TileEncoderPool {
    private final TileStore destino;
    private final float calidad;
    private final ThreadPoolExecutor pool;
    private final ThreadLocal<ImageWriter> escritores =
            ThreadLocal.withInitial(() -> ImageIO.getImageWritersByFormatName("jpeg").next());

    private final AtomicLong tiles = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicReference<Exception> primerError = new AtomicReference<>();

    public TileEncoderPool(TileStore destino, float calidad, int hilos, int capacidadCola) {
        this.destino = destino;
        this.calidad = calidad;
        this.pool = new ThreadPoolExecutor(hilos, hilos, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacidadCola),          // cola ACOTADA
                new ThreadPoolExecutor.CallerRunsPolicy());       // cola llena -> backpressure
    }

    /** Encola un tile para comprimirlo y guardarlo. */
    public void enviar(int z, int x, int y, BufferedImage tile) {
        pool.execute(() -> {
            try {
                byte[] jpeg = comprimir(tile);
                destino.escribir(z, x, y, jpeg);
                tiles.incrementAndGet();
                bytes.addAndGet(jpeg.length);
            } catch (Exception e) {
                primerError.compareAndSet(null, e);
            }
        });
    }

    private byte[] comprimir(BufferedImage tile) throws IOException {
        ImageWriter escritor = escritores.get();
        ImageWriteParam param = escritor.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(calidad);

        ByteArrayOutputStream salida = new ByteArrayOutputStream(64 * 1024);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(salida)) {
            escritor.setOutput(ios);
            escritor.write(null, new IIOImage(tile, null, null), param);
        }
        return salida.toByteArray();
    }

    /** Espera a que se compriman todos los tiles pendientes. */
    public void terminar() throws Exception {
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.DAYS);
        Exception e = primerError.get();
        if (e != null) {
            throw e;
        }
    }

    public long tiles() { return tiles.get(); }
    public long bytes() { return bytes.get(); }
}