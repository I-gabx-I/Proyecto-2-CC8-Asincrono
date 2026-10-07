package pimg.tiles;

/**
 * Política de reemplazo de la caché de tiles del servidor. Lógica pura: no lee disco ni usa locks
 * (TileCache la protege). Las claves son "imagen/z/x/y" y el límite se mide en bytes.
 */
public interface PoliticaCache {
    /** Datos del tile si está en caché (y registra el uso), o null. */
    byte[] obtener(String clave);

    /** Datos del tile si está en caché, SIN contarlo como un uso (no cambia su prioridad), o null. */
    byte[] mirar(String clave);

    /** Guarda un tile recién leído del disco y expulsa lo necesario para no pasar del límite. */
    void guardar(String clave, byte[] datos);

    int tiles();

    long bytes();

    /** Para el log: nombre y estado interno. */
    String describir();
}
