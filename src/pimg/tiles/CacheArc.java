package pimg.tiles;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ARC, Adaptive Replacement Cache (Megiddo y Modha, 2003), adaptada a un límite en BYTES.
 *
 *   T1: tiles usados una sola vez (recientes)      B1: claves expulsadas de T1 (fantasmas, sin datos)
 *   T2: tiles usados dos o más veces (frecuentes)  B2: claves expulsadas de T2 (fantasmas, sin datos)
 *
 * p es cuántos bytes "merece" T1. Un fallo que cae en B1 dice que T1 era chico: p sube. Uno que cae
 * en B2 dice que T2 era chico: p baja. Así la caché se ajusta sola entre recencia y frecuencia, y un
 * barrido (un cliente que recorre el nivel máximo una sola vez) se queda en T1 sin expulsar T2.
 */
public final class CacheArc implements PoliticaCache {
    private final long c;                                   // límite en bytes
    private long p = 0;                                     // objetivo de bytes para T1
    private final LinkedHashMap<String, byte[]> t1 = new LinkedHashMap<>();   // orden: primero = LRU
    private final LinkedHashMap<String, byte[]> t2 = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> b1 = new LinkedHashMap<>();  // clave -> tamaño
    private final LinkedHashMap<String, Integer> b2 = new LinkedHashMap<>();
    private long bt1, bt2, bb1, bb2;                         // bytes de cada lista

    public CacheArc(long limiteBytes) {
        this.c = limiteBytes;
    }

    @Override
    public byte[] obtener(String clave) {
        byte[] d = t1.remove(clave);
        if (d != null) {                                    // segundo uso: pasa a frecuentes
            bt1 -= d.length;
            t2.put(clave, d);
            bt2 += d.length;
            return d;
        }
        d = t2.remove(clave);
        if (d != null) {                                    // ya frecuente: al final (MRU) de T2
            t2.put(clave, d);
        }
        return d;
    }

    @Override
    public byte[] mirar(String clave) {
        byte[] d = t1.get(clave);               // get en un LinkedHashMap en orden de inserción no lo mueve
        return d != null ? d : t2.get(clave);
    }

    @Override
    public void guardar(String clave, byte[] datos) {
        if (t1.containsKey(clave) || t2.containsKey(clave)) {
            return;                                         // otro hilo ya lo guardó
        }
        int s = datos.length;
        if (s > c) {
            return;                                         // más grande que toda la caché: no se guarda
        }
        boolean enB2 = false;
        Integer fantasma = b1.remove(clave);
        if (fantasma != null) {                             // fallo en B1: T1 era demasiado chico
            double razon = Math.max(1.0, (double) b2.size() / (b1.size() + 1));
            p = Math.min(c, p + (long) (s * razon));
            bb1 -= fantasma;
            t2.put(clave, datos);                           // ya se usó dos veces: frecuente
            bt2 += s;
        } else if ((fantasma = b2.remove(clave)) != null) { // fallo en B2: T2 era demasiado chico
            double razon = Math.max(1.0, (double) b1.size() / (b2.size() + 1));
            p = Math.max(0, p - (long) (s * razon));
            bb2 -= fantasma;
            enB2 = true;
            t2.put(clave, datos);
            bt2 += s;
        } else {                                            // primera vez: reciente
            t1.put(clave, datos);
            bt1 += s;
        }
        while (bt1 + bt2 > c) {
            reemplazar(enB2);
        }
        // Fantasmas acotados como en el artículo: |T1| + |B1| ≤ c y el total ≤ 2c (aquí en bytes)
        while (bt1 + bb1 > c && !b1.isEmpty()) {
            bb1 -= quitarPrimero(b1);
        }
        while (bt1 + bt2 + bb1 + bb2 > 2 * c && !b2.isEmpty()) {
            bb2 -= quitarPrimero(b2);
        }
    }

    /** REPLACE del artículo: expulsa de T1 si pasa de su objetivo p; si no, de T2. Deja la clave como fantasma. */
    private void reemplazar(boolean pedidoEnB2) {
        boolean deT1 = !t1.isEmpty() && (bt1 > p || (pedidoEnB2 && bt1 >= p) || t2.isEmpty());
        LinkedHashMap<String, byte[]> origen = deT1 ? t1 : t2;
        Iterator<Map.Entry<String, byte[]>> it = origen.entrySet().iterator();
        Map.Entry<String, byte[]> e = it.next();
        int s = e.getValue().length;
        it.remove();
        if (deT1) {
            bt1 -= s;
            b1.put(e.getKey(), s);
            bb1 += s;
        } else {
            bt2 -= s;
            b2.put(e.getKey(), s);
            bb2 += s;
        }
    }

    private static int quitarPrimero(LinkedHashMap<String, Integer> fantasmas) {
        Iterator<Map.Entry<String, Integer>> it = fantasmas.entrySet().iterator();
        int s = it.next().getValue();
        it.remove();
        return s;
    }

    @Override public int tiles()  { return t1.size() + t2.size(); }
    @Override public long bytes() { return bt1 + bt2; }

    @Override
    public String describir() {
        return String.format("ARC T1 %d tiles (%d KB) / T2 %d tiles (%d KB), objetivo p %d KB",
                t1.size(), bt1 >> 10, t2.size(), bt2 >> 10, p >> 10);
    }
}
