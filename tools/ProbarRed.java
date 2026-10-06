import pimg.transporte.RedSimulada;

import java.util.Locale;

/**
 * Prueba de la Fase 5: RedSimulada por separado, sin servidor (PROTOCOLO.md §16).
 * Uso:  java -cp "out;tools\out" ProbarRed
 */
public class ProbarRed {
    private static final long MS = 1_000_000L;
    private static int fallos = 0;

    public static void main(String[] args) {
        // 1. Pérdida: 100 000 mensajes con PERD = 5 %
        RedSimulada r = new RedSimulada();
        r.configurar(5, 0, 0);
        int perdidos = 0;
        for (int i = 0; i < 100_000; i++) if (r.descartar()) perdidos++;
        double pct = perdidos / 1000.0;
        comprobar("1. Perdida 5 % en 100 000 mensajes", pct >= 4.8 && pct <= 5.2,
                String.format(Locale.ROOT, "%d perdidos (%.2f %%)", perdidos, pct));

        // 2. Ancho de banda: 100 mensajes de 10 000 B a 100 KB/s, todos listos en t = 0
        r = new RedSimulada();
        r.configurar(0, 100, 0);
        long primero = r.salida(0, 10_000), ultimo = primero;
        for (int i = 1; i < 100; i++) ultimo = r.salida(0, 10_000);
        comprobar("2. 100 x 10 KB a 100 KB/s", primero == 100 * MS && ultimo == 10_000 * MS,
                "primero " + primero / MS + " ms, ultimo " + ultimo / MS + " ms");

        // 3. Latencia: lo mismo con LAT = 80 ms (se suma a cada mensaje)
        r = new RedSimulada();
        r.configurar(0, 100, 80);
        primero = r.salida(0, 10_000);
        for (int i = 1; i < 100; i++) ultimo = r.salida(0, 10_000);
        comprobar("3. Igual con latencia 80 ms", primero == 180 * MS && ultimo == 10_080 * MS,
                "primero " + primero / MS + " ms, ultimo " + ultimo / MS + " ms");

        // 4. Enlace libre: un mensaje que llega cuando el enlace ya terminó no espera a nadie
        r = new RedSimulada();
        r.configurar(0, 100, 0);
        r.salida(0, 10_000);                               // ocupa el enlace de 0 a 100 ms
        long tarde = r.salida(5_000 * MS, 10_000);         // llega a los 5 s
        comprobar("4. Enlace libre: no acumula permisos", tarde == 5_100 * MS, "sale a los " + tarde / MS + " ms");

        // 5. Sin límites: sale en el mismo instante
        r = new RedSimulada();
        comprobar("5. Inactiva: sale al instante", r.inactiva() && r.salida(123, 50_000) == 123, "inactiva=" + r.inactiva());

        // 6. Semilla fija: dos redes iguales pierden exactamente los mismos mensajes
        RedSimulada a = new RedSimulada(), b = new RedSimulada();
        a.configurar(20, 0, 0);
        b.configurar(20, 0, 0);
        boolean iguales = true;
        for (int i = 0; i < 10_000; i++) if (a.descartar() != b.descartar()) iguales = false;
        comprobar("6. Semilla fija: experimento repetible", iguales, iguales ? "mismas perdidas" : "DISTINTAS");

        System.out.printf("%n%s%n", fallos == 0 ? "TODAS LAS PRUEBAS OK" : fallos + " PRUEBA(S) FALLARON");
    }

    private static void comprobar(String nombre, boolean ok, String detalle) {
        if (!ok) fallos++;
        System.out.printf("%-38s %s  %s%n", nombre, ok ? "OK   " : "FALLA", detalle);
    }
}