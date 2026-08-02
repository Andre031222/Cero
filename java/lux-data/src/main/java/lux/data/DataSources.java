package lux.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DataSources {

    public static final String DEFAULT = "default";

    private static final Map<String, Pool> POOLS = new ConcurrentHashMap<>();

    private DataSources() {
    }

    public static void register(String name, Pool pool) {
        POOLS.put(name, pool);
    }

    public static void registerDefault(Pool pool) {
        register(DEFAULT, pool);
    }

    public static Pool get(String name) {
        Pool pool = POOLS.get(name);
        if (pool == null) {
            throw new DataException("no hay un origen de datos llamado '" + name + "'"
                    + (POOLS.isEmpty() ? "; registra uno con DataSources.registerDefault(...)"
                    : "; registrados: " + POOLS.keySet()));
        }
        return pool;
    }

    public static Pool primary() {
        return get(DEFAULT);
    }

    public static boolean has(String name) {
        return POOLS.containsKey(name);
    }

    public static void clear() {
        POOLS.values().forEach(Pool::close);
        POOLS.clear();
    }
}
