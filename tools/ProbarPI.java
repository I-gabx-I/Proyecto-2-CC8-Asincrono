import pimg.transporte.ControladorPI;

import java.util.Locale;

/**
 * Prueba de la Fase 7: ControladorPI por separado, sin servidor (PROTOCOLO.md §12).
 * La "planta" es una cola: Q crece con (R − C)·Δt, donde C es lo que el enlace o el cliente
 * alcanzan a consumir, y el controlador ve Q con un reporte de retraso (100 ms).
 * Uso:  java -cp "out;tools\out" ProbarPI
 */
public class ProbarPI {
    private static final double DT = 0.1;
    private static int fallos = 0;

    public static void main(String[] args) {
        // 1. Ejemplo de §12.5: Q = 20 con integral en 0 → R = 40 − 48 < R_min → se satura y no integra
        ControladorPI pi = new ControladorPI();
        double r = pi.actualizar(20, DT, true);
        comprobar("1. Q = 20: satura en R_min, I sin cambio", r == 4 && pi.integral() == 0,
                fmt("R = %.1f, I = %.2f", r, pi.integral()));

        // 2. Luego Q = 4: e = +4, R = 40 + 16 + 8*0.4 = 59.2
        r = pi.actualizar(4, DT, true);
        comprobar("2. Luego Q = 4: R = 40 + 16 + Ki*I", Math.abs(r - 59.2) < 1e-9, fmt("R = %.1f", r));

        // 3. Reposo: 100 reportes con Q = 0 y nada que enviar → la integral no crece
        pi = new ControladorPI();
        for (int i = 0; i < 100; i++) r = pi.actualizar(0, DT, false);
        comprobar("3. Reposo: la integral no se dispara", pi.integral() == 0 && r == 72, fmt("R = %.1f, I = %.2f", r, pi.integral()));

        // 4. Con demanda y Q = 0 todo el tiempo: sube hasta R_max y ahí se congela la integral
        pi = new ControladorPI();
        for (int i = 0; i < 200; i++) r = pi.actualizar(0, DT, true);
        double iCongelada = pi.integral();
        pi.actualizar(0, DT, true);
        comprobar("4. Saturada arriba: integral congelada", r == 400 && pi.integral() == iCongelada,
                fmt("R = %.0f, I = %.2f", r, iCongelada));

        // 5. Respuesta en la planta simulada
        System.out.println();
        Resultado lento = planta(new ControladorPI(), 10, 10, 20);
        Resultado rapido = planta(new ControladorPI(), 200, 200, 20);
        Resultado escalon = planta(new ControladorPI(), 200, 10, 20);
        System.out.println("5. Planta simulada (Kp = 4, Ki = 8, Q* = 8):");
        System.out.println("   " + lento.texto("enlace de 10 tiles/s       "));
        System.out.println("   " + rapido.texto("enlace de 200 tiles/s      "));
        System.out.println("   " + escalon.texto("escalon 200 -> 10 en t=10 s"));
        comprobar("   Las tres terminan en Q = 8 +- 1",
                Math.abs(lento.qFinal - 8) < 1 && Math.abs(rapido.qFinal - 8) < 1 && Math.abs(escalon.qFinal - 8) < 1, "");

        // 6. Ganancia excesiva: con Kp = 10 y el retraso del reporte, el lazo oscila
        Resultado alto = planta(new ControladorPI(10, 10), 200, 200, 20);
        System.out.println("   " + alto.texto("Kp = 10, Ki = 10, 200/s   "));
        comprobar("6. Kp = 10 no se establece (inestable)", Math.abs(alto.qFinal - 8) > 1 || alto.establecimiento > 15, "");

        System.out.printf("%n%s%n", fallos == 0 ? "TODAS LAS PRUEBAS OK" : fallos + " PRUEBA(S) FALLARON");
    }

    private record Resultado(double qMax, double establecimiento, double qFinal, double rFinal) {
        String texto(String nombre) {
            return fmt("%s  Q max %5.1f | se establece en %4.1f s | Q final %4.1f | R final %5.1f",
                    nombre, qMax, establecimiento, qFinal, rFinal);
        }
    }

    /** C cambia de c1 a c2 a los 10 s (si son iguales, no hay escalón). Se mide desde el escalón. */
    private static Resultado planta(ControladorPI pi, double c1, double c2, double segundos) {
        double q = 0, qMedida = 0, r = pi.tasa(), qMax = 0, ultimoFuera = 0, desde = c1 == c2 ? 0 : 10;
        for (int k = 0; k < segundos / DT; k++) {
            double t = k * DT;
            double c = t < 10 ? c1 : c2;
            double qReportada = qMedida;          // el reporte llega con un paso de retraso
            qMedida = q;
            r = pi.actualizar(qReportada, DT, true);
            q = Math.max(0, q + (r - c) * DT);
            if (t >= desde) {
                qMax = Math.max(qMax, q);
                if (Math.abs(q - 8) > 2) ultimoFuera = t - desde;
            }
        }
        return new Resultado(qMax, ultimoFuera, q, r);
    }

    private static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }

    private static void comprobar(String nombre, boolean ok, String detalle) {
        if (!ok) fallos++;
        System.out.printf("%-44s %s  %s%n", nombre, ok ? "OK" : "FALLA", detalle);
    }
}