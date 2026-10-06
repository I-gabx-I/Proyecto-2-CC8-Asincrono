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

/** Codifica tiles (PNG o JPEG) y los guarda, en paralelo. Trabajo de CPU: hilos de plataforma. */
public final class TileEncoderPool {
    private final TileStore destino;
    private final String formato;          // "png" o "jpeg" (nombre de ImageIO)
    private final float calidadJpeg;       // solo se usa con JPEG
    private final ThreadPoolExecutor pool;
    private final ThreadLocal<ImageWriter> escritores;   // los ImageWriter no se comparten entre hilos

    private final AtomicLong tiles = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicReference<Exception> primerError = new AtomicReference<>();

    public TileEncoderPool(TileStore destino, String formato, float calidadJpeg, int hilos, int capacidadCola) {
        if (!ImageIO.getImageWritersByFormatName(formato).hasNext()) {
            throw new IllegalArgumentException("Formato de tile no soportado: " + formato);
        }
        this.destino = destino;
        this.formato = formato;
        this.calidadJpeg = calidadJpeg;
        this.escritores = ThreadLocal.withInitial(() -> ImageIO.getImageWritersByFormatName(formato).next());
        this.pool = new ThreadPoolExecutor(hilos, hilos, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacidadCola),          // cola ACOTADA
                Thread.ofPlatform().name("codificador-", 0)       // hilos de plataforma (trabajo de CPU)
                      .daemon().factory(),                        // daemon: si main falla, la JVM termina
                new ThreadPoolExecutor.CallerRunsPolicy());       // cola llena -> backpressure
    }

    /** Encola un tile para codificarlo y guardarlo. */
    public void enviar(int z, int x, int y, BufferedImage tile) {
        pool.execute(() -> {
            try {
                byte[] datos = codificar(tile);
                destino.escribir(z, x, y, datos);
                tiles.incrementAndGet();
                bytes.addAndGet(datos.length);
            } catch (Exception e) {
                primerError.compareAndSet(null, e);
            }
        });
    }

    private byte[] codificar(BufferedImage tile) throws IOException {
        ImageWriter escritor = escritores.get();
        ImageWriteParam param = escritor.getDefaultWriteParam();
        if (formato.equals("jpeg")) {                     // PNG no tiene "calidad": es sin pérdida
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(calidadJpeg);
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream(64 * 1024);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(salida)) {
            escritor.setOutput(ios);
            escritor.write(null, new IIOImage(tile, null, null), param);
        }
        return salida.toByteArray();
    }

    /** Espera a que se codifiquen todos los tiles pendientes. */
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