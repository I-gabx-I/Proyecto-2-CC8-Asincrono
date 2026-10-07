package pimg.tiles;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** LRU: expulsa el tile usado hace más tiempo. Se conserva para comparar con ARC (D-30). */
public final class CacheLru implements PoliticaCache {
    private final long limite;
    // accessOrder = true: cada get() mueve la entrada al final -> la primera es la menos usada
    private final LinkedHashMap<String, byte[]> mapa = new LinkedHashMap<>(1024, 0.75f, true);
    private long bytes = 0;

    public CacheLru(long limiteBytes) {
        this.limite = limiteBytes;
    }

    @Override
    public byte[] obtener(String clave) {
        return mapa.get(clave);
    }

    @Override
    public byte[] mirar(String clave) {
        return mapa.get(clave);                 // en LRU, el tile acaba de enviarse: ya es el más reciente
    }

    @Override
    public void guardar(String clave, byte[] datos) {
        if (datos.length > limite) return;                  // más grande que toda la caché: no se guarda
        byte[] anterior = mapa.put(clave, datos);
        bytes += datos.length - (anterior == null ? 0 : anterior.length);
        Iterator<Map.Entry<String, byte[]>> it = mapa.entrySet().iterator();
        while (bytes > limite && it.hasNext()) {
            bytes -= it.next().getValue().length;
            it.remove();
        }
    }

    @Override public int tiles()  { return mapa.size(); }
    @Override public long bytes() { return bytes; }
    @Override public String describir() { return "LRU"; }
}
