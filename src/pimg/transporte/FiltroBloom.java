package pimg.transporte;

/**
 * Filtro de Bloom con el contrato exacto de PROTOCOLO.md §13.3 (idéntico al de web/js/transporte/bloom.js).
 *
 *   m = 4096 bits, k = 7.  Clave = SEM (4 B) ‖ Z (1 B) ‖ X (4 B) ‖ Y (4 B), big-endian.
 *   h1 = FNV-1a de 32 bits;  h2 = fmix32(h1) | 1;  posᵢ = (h1 + i·h2 mod 2³²) mod m  (doble hashing).
 *   El bit j está en el byte j >> 3 con máscara 1 << (j & 7).
 *
 * Lógica pura. Puede dar falsos positivos (≈ 0.17 % con 300 tiles), nunca falsos negativos.
 */
public final class FiltroBloom {
    public static final int M = 4096;
    public static final int K = 7;
    public static final int BYTES = M / 8;

    private final int semilla;
    private final byte[] bits;

    /** Filtro vacío. */
    public FiltroBloom(int semilla) {
        this(semilla, new byte[BYTES]);
    }

    /** Filtro recibido del cliente (BLOOM o RESUME). */
    public FiltroBloom(int semilla, byte[] bits) {
        if (bits.length != BYTES) {
            throw new IllegalArgumentException("El filtro debe medir " + BYTES + " bytes");
        }
        this.semilla = semilla;
        this.bits = bits.clone();
    }

    public void agregar(int z, int x, int y) {
        for (int p : posiciones(semilla, z, x, y)) {
            bits[p >>> 3] |= (byte) (1 << (p & 7));
        }
    }

    /** false = seguro que no lo tiene; true = probablemente lo tiene. */
    public boolean contiene(int z, int x, int y) {
        for (int p : posiciones(semilla, z, x, y)) {
            if ((bits[p >>> 3] & (1 << (p & 7))) == 0) {
                return false;
            }
        }
        return true;
    }

    public int semilla() { return semilla; }
    public byte[] bytes() { return bits.clone(); }

    public int bitsEncendidos() {
        int n = 0;
        for (byte b : bits) {
            n += Integer.bitCount(b & 0xFF);
        }
        return n;
    }

    // ---------- Hashes (§13.3) ----------

    public static int[] posiciones(int semilla, int z, int x, int y) {
        int h1 = h1(semilla, z, x, y);
        int h2 = fmix32(h1) | 1;                      // impar: recorre todas las posiciones
        int[] p = new int[K];
        for (int i = 0; i < K; i++) {
            p[i] = Integer.remainderUnsigned(h1 + i * h2, M);   // int desborda = mod 2³²
        }
        return p;
    }

    /** FNV-1a de 32 bits sobre los 13 bytes de la clave. */
    public static int h1(int semilla, int z, int x, int y) {
        int h = 0x811C9DC5;
        h = entero(h, semilla);
        h = octeto(h, z);
        h = entero(h, x);
        h = entero(h, y);
        return h;
    }

    /** Finalizador de MurmurHash3. */
    public static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    private static int octeto(int h, int b) {
        return (h ^ (b & 0xFF)) * 0x01000193;
    }

    private static int entero(int h, int v) {                  // 4 bytes big-endian
        for (int s = 24; s >= 0; s -= 8) {
            h = octeto(h, v >>> s);
        }
        return h;
    }
}