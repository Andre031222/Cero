package corvo.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dónde viven los datos de sesión.
 *
 * <p>Por defecto, en la memoria del proceso: rápido y suficiente para una sola instancia. Detrás
 * de un balanceador con varias instancias hace falta un almacén compartido, y ese es el hueco que
 * cubre esta interfaz — Redis, una tabla, lo que sea. LuxCore no trae ninguna implementación
 * remota porque eso significaría una dependencia externa, que es justo lo que el proyecto evita.
 *
 * <pre>
 *   ServerOptions.builder().sessionStore(miAlmacenCompartido).build();
 * </pre>
 *
 * <p>Se escribe en cada cambio, no al final de la petición: así dos instancias nunca ven datos de
 * sesión distintos, aunque cueste un viaje más.
 */
public interface SessionStore {

    /** Lo guardado de una sesión. {@code null} en {@link #load} significa que no existe. */
    record Datos(Map<String, Object> valores, long creada, long ultimoAcceso) {
    }

    Datos load(String id);

    void save(String id, Datos datos);

    /**
     * Aplica un cambio sobre lo guardado, leyendo lo último que haya.
     *
     * <p>Existe porque {@link #save} pisa: cada petición trabajaba con su propia copia de los
     * valores y volcaba el mapa entero, así que con dos peticiones en vuelo sobre la misma sesión
     * —cosa normal, el navegador abre varias conexiones— la última en guardar borraba lo que
     * escribió la otra. En silencio: un token CSRF recién puesto, un mensaje, el paso de un
     * formulario largo.
     *
     * <p>La implementación por defecto es leer-modificar-escribir, que no es atómica. Un almacén
     * que pueda hacerlo de una sola vez —{@code compute} de un mapa concurrente, un
     * {@code UPDATE} condicional— debería sobrescribirla.
     */
    default void update(String id, java.util.function.UnaryOperator<Datos> cambio) {
        Datos siguiente = cambio.apply(load(id));
        if (siguiente != null) {
            save(id, siguiente);
        }
    }

    void remove(String id);

    /** Descarta lo caducado. Un almacén con caducidad propia puede no hacer nada. */
    default void sweep(long timeoutMillis) {
    }

    int size();

    static SessionStore inMemory() {
        return new EnMemoria();
    }

    final class EnMemoria implements SessionStore {

        private final Map<String, Datos> entradas = new ConcurrentHashMap<>();

        @Override
        public Datos load(String id) {
            return entradas.get(id);
        }

        @Override
        public void save(String id, Datos datos) {
            entradas.put(id, datos);
        }

        @Override
        public void update(String id, java.util.function.UnaryOperator<Datos> cambio) {
            entradas.compute(id, (ignorado, previo) -> cambio.apply(previo));
        }

        @Override
        public void remove(String id) {
            entradas.remove(id);
        }

        @Override
        public void sweep(long timeoutMillis) {
            long ahora = System.currentTimeMillis();
            entradas.values().removeIf(datos -> ahora - datos.ultimoAcceso() > timeoutMillis);
        }

        @Override
        public int size() {
            return entradas.size();
        }
    }
}
