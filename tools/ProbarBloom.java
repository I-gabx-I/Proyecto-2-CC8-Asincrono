import pimg.transporte.FiltroBloom;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Random;

/**
 * Prueba de la Fase 8: FiltroBloom contra los vectores de PROTOCOLO.md §13.3 (los mismos que verifica bloom.js).
 * Uso:  java -cp "out;tools\out" ProbarBloom
 */
public class ProbarBloom {
    private static int fallos = 0;

    public static void main(String[] args) throws Exception {
        // 1. Vectores de prueba: SEM, z, x, y, h1, h2, posiciones
        Object[][] vectores = {
            {0, 0, 0, 0, "da0f62ef", "c1eeb577", new int[]{751, 2150, 3549, 852, 2251, 3650, 953}},
            {0, 3, 2, 1, "6f0995ef", "a0e26c1b", new int[]{1519, 522, 3621, 2624, 1627, 630, 3729}},
            {0, 10, 689, 689, "aa361541", "e7cbfa79", new int[]{1345, 4026, 2611, 1196, 3877, 2462, 1047}},
            {1, 0, 0, 0, "8187a466", "0d1c23d9", new int[]{1126, 2111, 3096, 4081, 970, 1955, 2940}},
            {1, 3, 2, 1, "ec8d7166", "cac4047f", new int[]{358, 1509, 2660, 3811, 866, 2017, 3168}},
            {1, 10, 689, 689, "0cc2a54c", "1a040545", new int[]{1356, 2705, 4054, 1307, 2656, 4005, 1258}},
        };
        for (Object[] v : vectores) {
            int sem = (int) v[0], z = (int) v[1], x = (int) v[2], y = (int) v[3];
            int h1 = FiltroBloom.h1(sem, z, x, y);
            int h2 = FiltroBloom.fmix32(h1) | 1;
            String obtenido = String.format("%08x %08x %s", h1, h2, Arrays.toString(FiltroBloom.posiciones(sem, z, x, y)));
            String esperado = v[4] + " " + v[5] + " " + Arrays.toString((int[]) v[6]);
            comprobar(String.format("1. SEM %d, tile (%d,%d,%d)", sem, z, x, y), esperado, obtenido);
        }

        // 2. Filtro con los tres tiles de SEM = 0: 21 bits y huella SHA-256 conocida
        FiltroBloom f = new FiltroBloom(0);
        f.agregar(0, 0, 0);
        f.agregar(3, 2, 1);
        f.agregar(10, 689, 689);
        String huella = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(f.bytes())).substring(0, 16);
        comprobar("2. Tres tiles: bits y SHA-256", "21 d6a5e4617c624c5e", f.bitsEncendidos() + " " + huella);
        comprobar("   Base64 de 512 bytes", "684", String.valueOf(Base64.getEncoder().encodeToString(f.bytes()).length()));

        // 3. Sin falsos negativos y tasa de falsos positivos con 300 tiles (teórica ≈ 0.17 %)
        Random r = new Random(9);
        FiltroBloom lleno = new FiltroBloom(0);
        long[] dentro = new long[300];
        for (int i = 0; i < 300; i++) {
            int z = 8, x = r.nextInt(1000), y = r.nextInt(1000);
            lleno.agregar(z, x, y);
            dentro[i] = ((long) x << 20) | y;
        }
        boolean sinFalsosNegativos = true;
        for (long d : dentro) sinFalsosNegativos &= lleno.contiene(8, (int) (d >>> 20), (int) (d & 0xFFFFF));
        comprobar("3. 300 tiles: ningun falso negativo", "si", sinFalsosNegativos ? "si" : "NO");
        int falsos = 0, consultas = 200_000;
        for (int i = 0; i < consultas; i++) {
            if (lleno.contiene(9, r.nextInt(1_000_000), r.nextInt(1_000_000))) falsos++;   // z = 9: nunca agregados
        }
        System.out.printf(Locale.ROOT, "   Falsos positivos: %d de %d = %.3f %% (teorico 0.17 %%)%n",
                falsos, consultas, 100.0 * falsos / consultas);

        // 4. Cambiar la semilla mueve todas las posiciones
        comprobar("4. Otra SEM, otras posiciones", "false",
                String.valueOf(Arrays.equals(FiltroBloom.posiciones(0, 10, 689, 689), FiltroBloom.posiciones(1, 10, 689, 689))));

        System.out.printf("%n%s%n", fallos == 0 ? "TODAS LAS PRUEBAS OK" : fallos + " PRUEBA(S) FALLARON");
    }

    private static void comprobar(String nombre, String esperado, String obtenido) {
        boolean ok = esperado.equals(obtenido);
        if (!ok) fallos++;
        System.out.printf("%-36s %s%s%n", nombre, ok ? "OK" : "FALLA", ok ? "" : "  esperado [" + esperado + "] obtenido [" + obtenido + "]");
    }
}