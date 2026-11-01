package cero.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface SessionStore {

    record Datos(Map<String, Object> valores, long creada, long ultimoAcceso) {
    }

    Datos load(String id);

    void save(String id, Datos datos);

    default void update(String id, java.util.function.UnaryOperator<Datos> cambio) {
        Datos siguiente = cambio.apply(load(id));
        if (siguiente != null) {
            save(id, siguiente);
        }
    }

    void remove(String id);

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
