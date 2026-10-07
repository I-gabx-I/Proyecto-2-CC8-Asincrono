import pimg.tiles.CacheArc;
import pimg.tiles.CacheLru;
import pimg.tiles.PoliticaCache;

import java.util.Locale;
import java.util.Random;

/**
 * Prueba de la Fase 9: ARC contra LRU con la misma secuencia de pedidos (D-30, PROTOCOLO.md §17.1).
 * Caché de 100 tiles de 10 KB. Uso:  java -cp "out;tools\out" ProbarCache
 */
public class ProbarCache {
    private static final int TILE = 10_000;
    private static final long LIMITE = 100L * TILE;
    private static int fallos = 0;

    public static void main(String[] args) {
        // 1. Zona "caliente" + barrido: 60 tiles que varios clientes ven una y otra vez (cada uno se pide
        //    varias veces seguidas) y, entre cada vuelta, un cliente que recorre 150 tiles nuevos una sola
        //    vez. El barrido es más grande que la caché (100 tiles): con LRU borra la zona caliente.
        double[] lru = calienteMasBarrido(new CacheLru(LIMITE));
        double[] arc = calienteMasBarrido(new CacheArc(LIMITE));
        System.out.printf(Locale.ROOT, "1. Zona caliente + barrido   LRU: %5.1f %% de aciertos en la zona caliente%n", lru[0]);
        System.out.printf(Locale.ROOT, "                              ARC: %5.1f %% de aciertos en la zona caliente%n", arc[0]);
        comprobar("   ARC protege la zona caliente del barrido", arc[0] > lru[0] + 30);

        // 2. Navegación normal (ventana que se desplaza): ARC no debe ser peor que LRU
        double vLru = ventana(new CacheLru(LIMITE)), vArc = ventana(new CacheArc(LIMITE));
        System.out.printf(Locale.ROOT, "2. Ventana deslizante        LRU: %5.1f %%   ARC: %5.1f %%%n", vLru, vArc);
        comprobar("   ARC no es peor en navegacion normal (+-5 %)", vArc >= vLru - 5);

        // 3. Nunca pasa del límite, con tamaños de tile distintos
        PoliticaCache c = new CacheArc(LIMITE);
        Random r = new Random(3);
        boolean dentro = true;
        for (int i = 0; i < 200_000; i++) {
            String k = "img/" + r.nextInt(2000);
            if (c.obtener(k) == null) c.guardar(k, new byte[1_000 + r.nextInt(30_000)]);
            dentro &= c.bytes() <= LIMITE;
        }
        comprobar("3. 200 000 pedidos de tamano variable: nunca pasa de " + LIMITE / 1000 + " KB", dentro);

        System.out.printf("%n%s%n", fallos == 0 ? "TODAS LAS PRUEBAS OK" : fallos + " PRUEBA(S) FALLARON");
    }

    private static double[] calienteMasBarrido(PoliticaCache c) {
        int aciertos = 0, pedidos = 0, nuevo = 0;
        for (int vuelta = 0; vuelta < 50; vuelta++) {
            for (int i = 0; i < 60; i++) {
                boolean acierto = pedir(c, "caliente/" + i);
                if (vuelta >= 3) {                       // tras calentar la caché
                    pedidos++;
                    if (acierto) aciertos++;
                }
                pedir(c, "caliente/" + i);               // otro cliente pide el mismo tile
            }
            for (int i = 0; i < 150; i++) pedir(c, "barrido/" + nuevo++);
        }
        return new double[]{100.0 * aciertos / pedidos};
    }

    private static double ventana(PoliticaCache c) {
        int aciertos = 0, pedidos = 0;
        for (int paso = 0; paso < 500; paso++) {
            for (int i = 0; i < 50; i++) {               // 50 tiles visibles; la vista avanza 5 por paso
                pedidos++;
                if (pedir(c, "v/" + (paso * 5 + i))) aciertos++;
            }
        }
        return 100.0 * aciertos / pedidos;
    }

    private static boolean pedir(PoliticaCache c, String clave) {
        if (c.obtener(clave) != null) return true;
        c.guardar(clave, new byte[TILE]);
        return false;
    }

    private static void comprobar(String nombre, boolean ok) {
        if (!ok) fallos++;
        System.out.printf("%-58s %s%n", nombre, ok ? "OK" : "FALLA");
    }
}
