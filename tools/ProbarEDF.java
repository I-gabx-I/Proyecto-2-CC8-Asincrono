import pimg.transporte.PlanificadorEDF;
import pimg.transporte.PlanificadorEDF.Turno;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Prueba de la Fase 4: PlanificadorEDF por separado, sin servidor (PROTOCOLO.md §14).
 * Uso:  java -cp "out;tools\out" ProbarEDF
 */
public class ProbarEDF {
    private static int fallos = 0;

    public static void main(String[] args) {
        ordenPorPlazo();
        empates();
        sinPlazoAlFinal();
        formula();
        tarde();
        cancelar();
        aleatorio();
        rendimiento();
        System.out.printf("%n%s%n", fallos == 0 ? "TODAS LAS PRUEBAS OK" : fallos + " PRUEBA(S) FALLARON");
    }

    private static void ordenPorPlazo() {
        PlanificadorEDF<String> p = new PlanificadorEDF<>();
        p.agregar("50", 50); p.agregar("10", 10); p.agregar("40", 40); p.agregar("20", 20); p.agregar("30", 30);
        comprobar("1. Sale el de plazo mas proximo", "10 20 30 40 50", vaciar(p));
    }

    private static void empates() {
        PlanificadorEDF<String> p = new PlanificadorEDF<>();
        p.agregar("A", 100); p.agregar("x", 50); p.agregar("B", 100); p.agregar("C", 100);
        comprobar("2. Empates en orden de insercion", "x A B C", vaciar(p));
    }

    private static void sinPlazoAlFinal() {
        PlanificadorEDF<String> p = new PlanificadorEDF<>();
        p.agregar("DONE", PlanificadorEDF.SIN_PLAZO);       // se inserta PRIMERO
        p.agregar("t2", 200); p.agregar("t1", 100);
        comprobar("3. DONE (sin plazo) siempre al final", "t1 t2 DONE", vaciar(p));
    }

    private static void formula() {
        long centro = PlanificadorEDF.plazoTile(0, 0.5);
        long borde = PlanificadorEDF.plazoTile(0, 4.0);
        comprobar("4. Plazo con dist = 0.5 (ns)", "42500000", String.valueOf(centro));
        comprobar("   Plazo con dist = 4 (ns)", "130000000", String.valueOf(borde));
    }

    private static void tarde() {
        PlanificadorEDF<String> p = new PlanificadorEDF<>();
        p.agregar("a tiempo", 100); p.agregar("justo", 200); p.agregar("tarde", 300);
        p.agregar("DONE", PlanificadorEDF.SIN_PLAZO);
        String r = marca(p.extraer(50)) + " " + marca(p.extraer(200)) + " " + marca(p.extraer(301)) + " "
                + marca(p.extraer(Long.MAX_VALUE - 1));
        comprobar("5. Tarde solo si ahora > plazo", "ok ok TARDE ok", r);
        comprobar("   Contadores (atendidos/tardes/%)", "3/1/33.3",
                p.atendidos() + "/" + p.tardes() + "/" + String.format(Locale.ROOT, "%.1f", p.porcentajeTarde()));
    }

    private static void cancelar() {
        PlanificadorEDF<Integer> p = new PlanificadorEDF<>();
        for (int seq = 1; seq <= 5; seq++) p.agregar(seq, seq * 10L);
        int quitados = p.quitarSi(seq -> seq <= 3);          // CANCEL|SEQ:3
        comprobar("6. CANCEL quita SEQ <= 3", "3 quitados: 4 5", quitados + " quitados: " + vaciar(p));
    }

    /** 10 000 colas de 300 plazos al azar: lo extraído siempre sale en orden no decreciente. */
    private static void aleatorio() {
        Random r = new Random(42);
        boolean ok = true;
        for (int rep = 0; rep < 10_000 && ok; rep++) {
            PlanificadorEDF<Long> p = new PlanificadorEDF<>();
            for (int i = 0; i < 300; i++) {
                long plazo = r.nextInt(50);                  // rango chico: muchos empates
                p.agregar(plazo * 1000 + i, plazo);          // el trabajo guarda plazo e índice
            }
            long anterior = -1;
            while (!p.estaVacia()) {
                long t = p.extraer(0).trabajo();
                if (t < anterior) ok = false;                // plazo menor, o mismo plazo e índice menor
                anterior = t;
            }
        }
        comprobar("7. 10 000 colas al azar de 300", "orden correcto", ok ? "orden correcto" : "DESORDEN");
    }

    private static void rendimiento() {
        List<Long> plazos = new ArrayList<>();
        for (long i = 0; i < 300; i++) plazos.add(i);
        Collections.shuffle(plazos, new Random(7));
        PlanificadorEDF<Long> p = new PlanificadorEDF<>();
        int reps = 20_000;
        long t0 = System.nanoTime();
        for (int rep = 0; rep < reps; rep++) {
            for (long x : plazos) p.agregar(x, x);
            while (!p.estaVacia()) p.extraer(0);
        }
        double nsPorTrabajo = (System.nanoTime() - t0) / (double) (reps * 300L);
        System.out.printf("8. Rendimiento: %.0f ns por trabajo (agregar + extraer, cola de 300)%n", nsPorTrabajo);
    }

    // ---------- utilidades ----------

    private static String vaciar(PlanificadorEDF<?> p) {
        StringBuilder sb = new StringBuilder();
        while (!p.estaVacia()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.extraer(0).trabajo());
        }
        return sb.toString();
    }

    private static String marca(Turno<?> t) {
        return t.tarde() ? "TARDE" : "ok";
    }

    private static void comprobar(String nombre, String esperado, String obtenido) {
        boolean ok = esperado.equals(obtenido);
        if (!ok) fallos++;
        System.out.printf("%-38s %s%s%n", nombre, ok ? "OK  " : "FALLA", ok ? "" : "  esperado [" + esperado + "] obtenido [" + obtenido + "]");
    }
}