import pimg.transporte.FecXor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * Prueba de la Fase 6: FecXor por separado, sin servidor (PROTOCOLO.md §11).
 * Uso:  java -cp "out;tools\out" ProbarFec
 */
public class ProbarFec {
    private static int fallos = 0;

    public static void main(String[] args) {
        // 1. Agrupamiento entrelazado de §11.3
        comprobar("1. Grupos con 16 tiles", "[0,4,8,12] [1,5,9,13] [2,6,10,14] [3,7,11,15]", texto(FecXor.grupos(16)));
        comprobar("   Con 40 tiles (solo 16 protegidos)", texto(FecXor.grupos(16)), texto(FecXor.grupos(40)));
        comprobar("   Con 5 tiles (G = 2)", "[0,2,4] [1,3]", texto(FecXor.grupos(5)));
        comprobar("   Con 1 tile (no se protege)", "", texto(FecXor.grupos(1)));

        // 2. Ráfagas: con 16 tiles, cualquier ráfaga de hasta 4 pérdidas seguidas toca 1 tile por grupo
        boolean rafagasOk = true;
        for (int largo = 1; largo <= 4; largo++) {
            for (int inicio = 0; inicio + largo <= 16; inicio++) {
                for (int[] g : FecXor.grupos(16)) {
                    int tocados = 0;
                    for (int r : g) if (r >= inicio && r < inicio + largo) tocados++;
                    if (tocados > 1) rafagasOk = false;
                }
            }
        }
        comprobar("2. Rafagas de 1 a 4: max 1 por grupo", "si", rafagasOk ? "si" : "NO");

        // 3. Reconstrucción: 2000 grupos al azar de 2 a 4 tiles de 1 a 40 000 bytes; se quita cada miembro
        Random r = new Random(5);
        int pruebas = 0, correctas = 0;
        for (int rep = 0; rep < 2000; rep++) {
            int k = 2 + r.nextInt(3);
            List<byte[]> datos = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                byte[] d = new byte[1 + r.nextInt(40_000)];
                r.nextBytes(d);
                datos.add(d);
            }
            byte[] p = FecXor.paridad(datos);
            for (int falta = 0; falta < k; falta++) {
                List<byte[]> presentes = new ArrayList<>(datos);
                byte[] original = presentes.remove(falta);
                byte[] rec = FecXor.reconstruir(p, presentes, original.length);
                pruebas++;
                if (Arrays.equals(rec, original)) correctas++;
            }
        }
        comprobar("3. Reconstruccion de 1 faltante", pruebas + "/" + pruebas, correctas + "/" + pruebas);

        // 4. Con 2 faltantes en el mismo grupo, el CRC detecta que la reconstrucción es inválida
        List<byte[]> grupo = new ArrayList<>();
        for (int i = 0; i < 4; i++) { byte[] d = new byte[30_000]; r.nextBytes(d); grupo.add(d); }
        byte[] p = FecXor.paridad(grupo);
        byte[] intento = FecXor.reconstruir(p, List.of(grupo.get(0), grupo.get(1)), grupo.get(2).length);
        comprobar("4. Dos faltantes: el CRC lo rechaza", "distinto", crc(intento) == crc(grupo.get(2)) ? "IGUAL" : "distinto");

        // 5. Costo: 16 tiles de 20 a 40 KB (tamaños parecidos a los PNG reales)
        long protegidos = 0, extra = 0;
        List<byte[]> tiles = new ArrayList<>();
        for (int i = 0; i < 16; i++) { byte[] d = new byte[20_000 + r.nextInt(20_000)]; tiles.add(d); protegidos += d.length; }
        for (int[] g : FecXor.grupos(16)) {
            List<byte[]> miembros = new ArrayList<>();
            for (int i : g) miembros.add(tiles.get(i));
            extra += FecXor.paridad(miembros).length + 19 + 18 * g.length;
        }
        System.out.printf(Locale.ROOT, "5. Costo: %d bytes de paridad sobre %d protegidos = %.1f %%%n",
                extra, protegidos, 100.0 * extra / protegidos);

        System.out.printf("%n%s%n", fallos == 0 ? "TODAS LAS PRUEBAS OK" : fallos + " PRUEBA(S) FALLARON");
    }

    private static String texto(List<int[]> grupos) {
        StringBuilder sb = new StringBuilder();
        for (int[] g : grupos) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('[');
            for (int i = 0; i < g.length; i++) sb.append(i == 0 ? "" : ",").append(g[i]);
            sb.append(']');
        }
        return sb.toString();
    }

    private static long crc(byte[] d) {
        CRC32 c = new CRC32();
        c.update(d);
        return c.getValue();
    }

    private static void comprobar(String nombre, String esperado, String obtenido) {
        boolean ok = esperado.equals(obtenido);
        if (!ok) fallos++;
        System.out.printf("%-40s %s%s%n", nombre, ok ? "OK" : "FALLA", ok ? "" : "  esperado [" + esperado + "] obtenido [" + obtenido + "]");
    }
}