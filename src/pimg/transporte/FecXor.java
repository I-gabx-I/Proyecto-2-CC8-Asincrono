package pimg.transporte;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FEC con paridad XOR entrelazada (PROTOCOLO.md §11, RFC 5109).
 * Lógica pura: arma los grupos y calcula la paridad. No sabe de tiles, sockets ni disco.
 */
public final class FecXor {
    public static final int PROTEGIDOS = 16;   // N máximo: solo los tiles más prioritarios (§11.3)
    public static final int TAM_GRUPO = 4;     // máximo de miembros por grupo

    private FecXor() {}

    /**
     * Grupos entrelazados para n tiles ordenados por prioridad (rango 0 = el más prioritario).
     * N = min(16, n), G = ⌈N / 4⌉ y el rango i va al grupo i mod G (§11.3). Los grupos de un
     * solo tile se omiten: su paridad sería una copia. Devuelve los rangos de cada grupo, crecientes.
     */
    public static List<int[]> grupos(int n) {
        int total = Math.min(PROTEGIDOS, n);
        List<int[]> resultado = new ArrayList<>();
        if (total < 2) {
            return resultado;
        }
        int g = Math.ceilDiv(total, TAM_GRUPO);
        for (int grupo = 0; grupo < g; grupo++) {
            int miembros = (total - grupo + g - 1) / g;    // cuántos rangos i < total cumplen i mod g == grupo
            if (miembros < 2) {
                continue;
            }
            int[] rangos = new int[miembros];
            for (int j = 0; j < miembros; j++) {
                rangos[j] = grupo + j * g;
            }
            resultado.add(rangos);
        }
        return resultado;
    }

    /** XOR de todos los datos, cada uno rellenado con ceros hasta el más largo (§11.2). */
    public static byte[] paridad(List<byte[]> datos) {
        int largo = 0;
        for (byte[] d : datos) {
            largo = Math.max(largo, d.length);
        }
        byte[] p = new byte[largo];
        for (byte[] d : datos) {
            for (int i = 0; i < d.length; i++) {
                p[i] ^= d[i];
            }
        }
        return p;
    }

    /** Reconstruye el faltante: paridad ⊕ los demás miembros, recortado a su longitud (§11.5). */
    public static byte[] reconstruir(byte[] paridad, List<byte[]> presentes, int longitud) {
        byte[] c = paridad.clone();
        for (byte[] d : presentes) {
            for (int i = 0; i < d.length; i++) {
                c[i] ^= d[i];
            }
        }
        return Arrays.copyOf(c, longitud);
    }
}