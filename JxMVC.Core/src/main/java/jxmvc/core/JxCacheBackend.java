/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Interfaz para backends de caché configurables.
 * Implementar y anotar con {@code @JxService @JxCacheManager} para reemplazar
 * la caché en memoria predeterminada de {@link JxCache}.
 *
 * <pre>
 *   &#64;JxService
 *   &#64;JxCacheManager
 *   public class RedisCacheBackend implements JxCacheBackend {
 *       &#64;Override
 *       public void put(String cache, String key, Object value, long ttlSeconds) { ... }
 *
 *       &#64;Override
 *       public Object fetch(String cache, String key) { ... }
 *
 *       &#64;Override
 *       public void evict(String cache, String key) { ... }
 *
 *       &#64;Override
 *       public void clear(String cache) { ... }
 *
 *       &#64;Override
 *       public boolean has(String cache, String key) { ... }
 *   }
 * </pre>
 */
public interface JxCacheBackend {

    /**
     * Guarda {@code value} bajo {@code key} en la caché {@code cache}.
     *
     * @param cache      nombre de la caché
     * @param key        clave
     * @param value      valor a guardar
     * @param ttlSeconds TTL en segundos; 0 = sin expiración
     */
    void put(String cache, String key, Object value, long ttlSeconds);

    /**
     * Recupera el valor o {@code null} si no existe o expiró.
     *
     * @param cache nombre de la caché
     * @param key   clave
     */
    Object fetch(String cache, String key);

    /**
     * Elimina una entrada específica.
     *
     * @param cache nombre de la caché
     * @param key   clave a eliminar
     */
    void evict(String cache, String key);

    /**
     * Elimina todas las entradas de la caché.
     *
     * @param cache nombre de la caché
     */
    void clear(String cache);

    /**
     * Verifica si existe una entrada activa (no expirada).
     *
     * @param cache nombre de la caché
     * @param key   clave
     */
    boolean has(String cache, String key);
}
