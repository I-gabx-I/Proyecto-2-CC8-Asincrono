package pimg.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mensaje de control PIMG en texto: COMANDO|CLAVE:VALOR|... (PROTOCOLO.md §5.1) */
public final class Mensaje {
    private final String comando;
    private final Map<String, String> campos;

    private Mensaje(String comando, Map<String, String> campos) {
        this.comando = comando;
        this.campos = campos;
    }

    // ---------- Construir (servidor -> cliente) ----------

    public static Mensaje de(String comando) {
        return new Mensaje(comando, new LinkedHashMap<>());
    }

    public Mensaje con(String clave, Object valor) {
        campos.put(clave, String.valueOf(valor).replace('|', '/')); // '|' está prohibido en un VALOR
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(comando);
        campos.forEach((k, v) -> sb.append('|').append(k).append(':').append(v));
        return sb.toString();
    }

    // ---------- Parsear (cliente -> servidor) ----------

    public static Mensaje parsear(String texto) throws PimgException {
        String[] partes = texto.split("\\|", -1);
        String comando = partes[0];
        if (!comando.matches("[A-Z_]+")) {
            throw new PimgException(PimgException.MALFORMED, "Comando invalido");
        }
        Map<String, String> campos = new LinkedHashMap<>();
        for (int i = 1; i < partes.length; i++) {
            String parte = partes[i];
            int dosPuntos = parte.indexOf(':');            // el VALOR empieza tras el PRIMER ':'
            if (dosPuntos <= 0) {
                throw new PimgException(PimgException.MALFORMED, "Campo sin clave: " + parte);
            }
            String clave = parte.substring(0, dosPuntos);
            if (!clave.matches("[A-Z_]+")) {
                throw new PimgException(PimgException.MALFORMED, "Clave invalida: " + clave);
            }
            if (campos.putIfAbsent(clave, parte.substring(dosPuntos + 1)) != null) {
                throw new PimgException(PimgException.MALFORMED, "Clave repetida: " + clave);
            }
        }
        return new Mensaje(comando, campos);
    }

    public String comando() {
        return comando;
    }

    public String texto(String clave) throws PimgException {
        String v = campos.get(clave);
        if (v == null) {
            throw new PimgException(PimgException.MALFORMED, "Falta el campo " + clave);
        }
        return v;
    }

    /** Entero decimal dentro de [min, max]; si no, 400 MALFORMED. */
    public long entero(String clave, long min, long max) throws PimgException {
        String v = texto(clave);
        try {
            long n = Long.parseLong(v);
            if (n < min || n > max) {
                throw new PimgException(PimgException.MALFORMED, "Campo " + clave + " fuera de rango: " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new PimgException(PimgException.MALFORMED, "Campo " + clave + " no es un entero: " + v);
        }
    }
}