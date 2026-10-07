package pimg.tiles;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caché de tiles en RAM, COMPARTIDA por todas las sesiones y limitada en bytes (PROTOCOLO.md §17.1).
 * La política de reemplazo es ARC (D-30); LRU queda como opción para comparar (--lru).
 */
public final class TileCache {
    private final long limiteBytes;
    private final PoliticaCache politica;
    private final ReentrantLock lock = new ReentrantLock();
    private long aciertos = 0;
    private long fallos = 0;

    public TileCache(long limiteBytes, boolean usarLru) {
        this.limiteBytes = limiteBytes;
        this.politica = usarLru ? new CacheLru(limiteBytes) : new CacheArc(limiteBytes);
    }

    public byte[] obtener(String idImagen, TileStore almacen, int z, int x, int y) throws IOException {
        String clave = idImagen + "/" + z + "/" + x + "/" + y;

        lock.lock();
        try {
            byte[] datos = politica.obtener(clave);
            if (datos != null) {
                aciertos++;
                return datos;                       // acierto: sin tocar el disco
            }
            fallos++;
        } finally {
            lock.unlock();
        }

        byte[] datos = almacen.leer(z, x, y);       // disco FUERA del lock

        lock.lock();
        try {
            politica.guardar(clave, datos);
        } finally {
            lock.unlock();
        }
        return datos;
    }

    /**
     * Para calcular una paridad (§11.4): los miembros se acaban de enviar, así que leerlos otra vez NO es un
     * segundo uso. Con ARC, contarlo pasaría a "frecuentes" los tiles de un solo cliente. No cuenta como acierto.
     */
    public byte[] obtenerSinUso(String idImagen, TileStore almacen, int z, int x, int y) throws IOException {
        String clave = idImagen + "/" + z + "/" + x + "/" + y;
        lock.lock();
        try {
            byte[] datos = politica.mirar(clave);
            if (datos != null) {
                return datos;
            }
        } finally {
            lock.unlock();
        }
        return almacen.leer(z, x, y);               // expulsado entre el envío y la paridad: se lee sin guardarlo
    }

    public String estadisticas() {
        lock.lock();
        try {
            long total = aciertos + fallos;
            return String.format("cache %s: %d tiles, %d de %d MB, aciertos %d de %d (%.0f %%)",
                    politica.describir(), politica.tiles(), politica.bytes() >> 20, limiteBytes >> 20,
                    aciertos, total, total == 0 ? 0.0 : 100.0 * aciertos / total);
        } finally {
            lock.unlock();
        }
    }
}
