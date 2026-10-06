package pimg.transporte;

import java.util.Random;

/**
 * Modelo de un enlace con pérdida, ancho de banda y latencia (PROTOCOLO.md §16).
 *
 * - Pérdida: cada mensaje descartable se pierde con probabilidad PERD %, de forma independiente.
 *   Semilla fija: el mismo experimento produce las mismas pérdidas.
 * - Ancho de banda: retardo de TRANSMISIÓN (bytes / BW). El enlace queda ocupado mientras
 *   transmite y el siguiente mensaje espera a que se desocupe. No es una cubeta de tokens:
 *   no se acumulan permisos cuando el enlace está libre.
 * - Latencia: retardo de PROPAGACIÓN. El mensaje sale LAT ms después de terminar de transmitirse.
 *
 * Lógica pura: no lee el reloj ni usa hilos. Quien la usa le pasa el instante actual (ns de
 * System.nanoTime) y la protege con su propio lock.
 */
public final class RedSimulada {
    public static final long SEMILLA = 42;

    private final Random azar = new Random(SEMILLA);
    private int perdida = 0;       // %  (0..50)
    private int anchoKBs = 0;      // KB/s, 1 KB = 1000 bytes; 0 = sin límite
    private int latenciaMs = 0;    // ms
    private long libreDesde = 0;   // instante en que el enlace termina de transmitir lo anterior

    public void configurar(int perdida, int anchoKBs, int latenciaMs) {
        this.perdida = perdida;
        this.anchoKBs = anchoKBs;
        this.latenciaMs = latenciaMs;
    }

    /** true si no hay pérdida, límite ni latencia: el enlace se comporta como el socket directo. */
    public boolean inactiva() {
        return perdida == 0 && anchoKBs == 0 && latenciaMs == 0;
    }

    /** ¿Se pierde este mensaje? Un mensaje perdido no ocupa el enlace (se pierde a la entrada). */
    public boolean descartar() {
        return perdida > 0 && azar.nextInt(100) < perdida;
    }

    /** Instante (ns) en que el mensaje sale del enlace: espera a que se desocupe, se transmite y se propaga. */
    public long salida(long ahora, int bytes) {
        long inicio = Math.max(ahora, libreDesde);
        long transmision = anchoKBs == 0 ? 0 : bytes * 1_000_000L / anchoKBs;   // bytes / (KB/s · 1000) en ns
        libreDesde = inicio + transmision;
        return libreDesde + latenciaMs * 1_000_000L;
    }

    public int perdida()    { return perdida; }
    public int anchoKBs()   { return anchoKBs; }
    public int latenciaMs() { return latenciaMs; }
}