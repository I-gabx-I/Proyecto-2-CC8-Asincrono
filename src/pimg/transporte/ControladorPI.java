package pimg.transporte;

/**
 * Controlador PI del ritmo de envío (PROTOCOLO.md §12).
 *
 *   e = Q* − Q          I = I + e·Δt          R = clamp(R₀ + Kp·e + Ki·I, R_min, R_max)
 *
 * Q = tiles que el servidor ya soltó y el usuario todavía no ve. R = mensajes binarios por segundo.
 * Anti-windup por integración condicional (§12.5): la integral no se actualiza si R está saturada y el
 * error empuja más allá del límite, ni si el error es positivo y no hay nada que enviar (reposo).
 * Lógica pura: no lee el reloj ni usa hilos.
 */
public final class ControladorPI {
    public static final double Q_OBJETIVO = 8;     // Q*, tiles
    public static final double R0 = 40;            // tasa base, mensajes/s
    public static final double KP = 4;             // mensajes/s por tile de error
    public static final double KI = 8;             // mensajes/s por tile·s
    public static final double R_MIN = 4;
    public static final double R_MAX = 400;

    private final double kp;
    private final double ki;
    private double integral = 0;
    private double error = 0;
    private double tasa;

    public ControladorPI() {
        this(KP, KI);
    }

    public ControladorPI(double kp, double ki) {
        this.kp = kp;
        this.ki = ki;
        this.tasa = limitar(R0 + kp * Q_OBJETIVO);     // antes del primer reporte: Q = 0
    }

    /**
     * Un paso de control (cada REPORT).
     * @param q           ocupación medida, en tiles
     * @param dt          segundos desde el paso anterior
     * @param hayDemanda  el servidor tiene pedidos pendientes de enviar
     */
    public double actualizar(double q, double dt, boolean hayDemanda) {
        error = Q_OBJETIVO - q;
        double previa = R0 + kp * error + ki * integral;
        boolean saturadaArriba = previa >= R_MAX && error > 0;
        boolean saturadaAbajo = previa <= R_MIN && error < 0;
        boolean reposo = !hayDemanda && error > 0;     // sin nada que enviar, Q = 0 no es "espacio libre"
        if (!saturadaArriba && !saturadaAbajo && !reposo) {
            integral += error * dt;
        }
        tasa = limitar(R0 + kp * error + ki * integral);
        return tasa;
    }

    private static double limitar(double r) {
        return Math.max(R_MIN, Math.min(R_MAX, r));
    }

    public double tasa()     { return tasa; }
    public double error()    { return error; }
    public double integral() { return integral; }
}