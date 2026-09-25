package pimg.tiles;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Caché LRU de tiles en RAM, COMPARTIDA por todas las sesiones y limitada en bytes. */
public final class TileCache {
    private final long limiteBytes;
    private final ReentrantLock lock = new ReentrantLock();
    // accessOrder = true: cada get() mueve la entrada al final -> la primera es la menos usada
    private final LinkedHashMap<String, byte[]> mapa = new LinkedHashMap<>(1024, 0.75f, true);
    private long bytesUsados = 0;
    private long aciertos = 0;
    private long fallos = 0;

    public TileCache(long limiteBytes) {
        this.limiteBytes = limiteBytes;
    }

    public byte[] obtener(String idImagen, TileStore almacen, int z, int x, int y) throws IOException {
        String clave = idImagen + "/" + z + "/" + x + "/" + y;

        lock.lock();
        try {
            byte[] datos = mapa.get(clave);
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
            byte[] anterior = mapa.put(clave, datos);
            bytesUsados += datos.length - (anterior == null ? 0 : anterior.length);
            Iterator<Map.Entry<String, byte[]>> it = mapa.entrySet().iterator();
            while (bytesUsados > limiteBytes && it.hasNext()) {   // expulsar los menos usados
                bytesUsados -= it.next().getValue().length;
                it.remove();
            }
        } finally {
            lock.unlock();
        }
        return datos;
    }

    public String estadisticas() {
        lock.lock();
        try {
            return String.format("cache: %d tiles, %d de %d MB, aciertos %d, fallos %d",
                    mapa.size(), bytesUsados >> 20, limiteBytes >> 20, aciertos, fallos);
        } finally {
            lock.unlock();
        }
    }
}