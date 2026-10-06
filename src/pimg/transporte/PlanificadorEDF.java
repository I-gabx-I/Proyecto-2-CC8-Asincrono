package pimg.transporte;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Cola de envío por plazos: Earliest Deadline First (PROTOCOLO.md §14).
 *
 * Siempre entrega el trabajo de plazo más próximo; los empates salen en orden de inserción.
 * Lógica pura: no lee el reloj ni usa hilos. Quien la usa le pasa los plazos y el instante
 * actual (en nanosegundos de System.nanoTime) y la protege con su propio lock.
 */
public final class PlanificadorEDF<T> {
    /** D₀ y D₁ de §14.2, en nanosegundos. */
    public static final long D0_NS = 30_000_000L;
    public static final long D1_NS = 25_000_000L;
    /** Plazo de lo que va siempre al final (el DONE). Nunca cuenta como tarde. */
    public static final long SIN_PLAZO = Long.MAX_VALUE;

    /** Lo que entrega extraer(): el trabajo, su plazo y si salió después de él. */
    public record Turno<T>(T trabajo, long plazo, boolean tarde) {}

    private record Entrada<T>(T trabajo, long plazo, long orden) {}

    // Montículo binario: insertar y extraer en O(log n)
    private final PriorityQueue<Entrada<T>> cola = new PriorityQueue<>(
            Comparator.<Entrada<T>>comparingLong(Entrada::plazo).thenComparingLong(Entrada::orden));
    private long siguienteOrden = 0;   // desempate por orden de inserción
    private long atendidos = 0;        // trabajos con plazo que ya salieron
    private long tardes = 0;           // de ellos, cuántos salieron después de su plazo

    /** Plazo de un tile (§14.2): t₀ + D₀ + D₁ · dist, con dist en tiles (§6). */
    public static long plazoTile(long t0, double dist) {
        return t0 + D0_NS + Math.round(D1_NS * dist);
    }

    public void agregar(T trabajo, long plazo) {
        cola.add(new Entrada<>(trabajo, plazo, siguienteOrden++));
    }

    /** Saca el trabajo de plazo más próximo, o null si no hay. Cuenta si salió tarde (§14.4). */
    public Turno<T> extraer(long ahora) {
        Entrada<T> e = cola.poll();
        if (e == null) {
            return null;
        }
        boolean tarde = false;
        if (e.plazo() != SIN_PLAZO) {
            atendidos++;
            tarde = ahora > e.plazo();
            if (tarde) {
                tardes++;
            }
        }
        return new Turno<>(e.trabajo(), e.plazo(), tarde);
    }

    /** Vista nueva u OPEN: se descarta todo lo pendiente. */
    public void vaciar() {
        cola.clear();
    }

    /** Quita los trabajos que cumplen la condición (CANCEL). Devuelve cuántos quitó. */
    public int quitarSi(Predicate<? super T> condicion) {
        int antes = cola.size();
        cola.removeIf(e -> condicion.test(e.trabajo()));
        return antes - cola.size();
    }

    public boolean estaVacia() { return cola.isEmpty(); }
    public int tamanio()       { return cola.size(); }
    public long atendidos()    { return atendidos; }
    public long tardes()       { return tardes; }

    /** Porcentaje de trabajos que salieron después de su plazo (TARDE, §14.4). */
    public double porcentajeTarde() {
        return atendidos == 0 ? 0 : 100.0 * tardes / atendidos;
    }
}