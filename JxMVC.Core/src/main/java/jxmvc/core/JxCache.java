/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caché en memoria con TTL — cero dependencias externas.
 * Las cachés son nominadas y globales; se crean la primera vez que se solicitan.
 *
 * <pre>
 *   // Guardar con TTL de 5 minutos
 *   JxCache.get("users").put("all", rows, 300);
 *
 *   // Recuperar
 *   DBRowSet rows = JxCache.get("users").fetch("all", DBRowSet.class);
 *
 *   // Evict de un solo key
 *   JxCache.get("users").evict("all");
 *
 *   // Evict de toda la caché
 *   JxCache.evictAll("users");
 *
 *   // Patrón cache-aside
 *   var rows = JxCache.get("users").computeIfAbsent("all", 300, () -> db.getTable("users"));
 * </pre>
 *
 * Un daemon de limpieza elimina entradas expiradas cada 60 segundos automáticamente.
 * Para usar un backend externo (Redis, Memcached…) registrar un {@link JxCacheBackend}
 * vía {@link #setGlobalBackend(JxCacheBackend)}.
 *
 * <pre>
 * # application.properties
 * jxmvc.cache.maxEntries=10000   # tope por caché; al llenarse desaloja la menos accedida (0 = sin límite)
 * </pre>
 */
public final class JxCache {

    // ── Backend pluggable (Tier 3) ────────────────────────────────────────

    private static volatile JxCacheBackend globalBackend = null;

    /**
     * Establece un backend global de caché.
     * Llamar desde {@code init()} o desde un {@code @JxService @JxCacheManager}.
     * Una vez establecido, todas las operaciones de {@link JxCache} lo usan.
     */
    public static void setGlobalBackend(JxCacheBackend backend) {
        globalBackend = backend;
    }

    /** Retorna el backend configurado o {@code null} si se usa la implementación en memoria. */
    public static JxCacheBackend getGlobalBackend() {
        return globalBackend;
    }

    // ── Registro global de cachés nominadas ──────────────────────────────

    private static final ConcurrentHashMap<String, JxCache> REGISTRY = new ConcurrentHashMap<>();

    private static final java.util.concurrent.ScheduledExecutorService CLEANER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jx-cache-cleaner");
                t.setDaemon(true);
                return t;
            });

    static {
        CLEANER.scheduleWithFixedDelay(JxCache::cleanAll, 60, 60, TimeUnit.SECONDS);
    }

    /** Detiene el daemon de limpieza — llamar en el shutdown del contenedor (evita fuga de classloader). */
    public static void shutdown() {
        CLEANER.shutdownNow();
    }

    /** Retorna (o crea) la caché de nombre {@code name}. */
    public static JxCache get(String name) {
        return REGISTRY.computeIfAbsent(name, k -> new JxCache(k));
    }

    /** Limpia todas las entradas de la caché {@code name}. */
    public static void evictAll(String name) {
        if (globalBackend != null) { globalBackend.clear(name); return; }
        JxCache c = REGISTRY.get(name);
        if (c != null) c.clear();
    }

    /** Limpia entradas expiradas en todas las cachés. */
    public static void cleanAll() {
        if (globalBackend != null) return;   // el backend externo maneja su propio TTL
        REGISTRY.values().forEach(JxCache::cleanExpired);
    }

    // ── Instancia ─────────────────────────────────────────────────────────

    private static final int MAX_ENTRIES =
            BaseDbResolver.propertyInt("jxmvc.cache.maxEntries", 10_000);

    private final String name;
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private JxCache(String name) { this.name = name; }

    /** Guarda {@code value} bajo {@code key} sin expiración. */
    public JxCache put(String key, Object value) {
        return put(key, value, 0);
    }

    /** Guarda {@code value} bajo {@code key} con TTL en segundos. */
    public JxCache put(String key, Object value, long ttlSeconds) {
        if (globalBackend != null) { globalBackend.put(name, key, value, ttlSeconds); return this; }
        long expireAt = ttlSeconds > 0
                ? System.currentTimeMillis() + ttlSeconds * 1000
                : 0;
        ensureCapacity(key);
        store.put(key, new Entry(value, expireAt));
        return this;
    }

    private void ensureCapacity(String key) {
        if (MAX_ENTRIES <= 0 || store.size() < MAX_ENTRIES || store.containsKey(key)) return;
        cleanExpired();
        while (store.size() >= MAX_ENTRIES) {
            String coldest = coldestKey();
            if (coldest == null) return;
            store.remove(coldest);
            locks.remove(coldest);
        }
    }

    /**
     * LRU aproximado por muestreo (estilo Redis): examina hasta {@code EVICT_SAMPLE}
     * entradas y desaloja la más fría de la muestra. Evita el escaneo O(n) del mapa
     * completo en cada {@code put} cuando la caché está a tope.
     */
    private static final int EVICT_SAMPLE = 64;

    private String coldestKey() {
        String coldest = null;
        long oldest = Long.MAX_VALUE;
        int seen = 0;
        for (var e : store.entrySet()) {
            long access = e.getValue().lastAccess;
            if (access < oldest) { oldest = access; coldest = e.getKey(); }
            if (++seen >= EVICT_SAMPLE) break;
        }
        return coldest;
    }

    /** Retorna el valor si existe y no expiró, o {@code null}. */
    @SuppressWarnings("unchecked")
    public <T> T fetch(String key, Class<T> type) {
        if (globalBackend != null) {
            Object v = globalBackend.fetch(name, key);
            return type.isInstance(v) ? (T) v : null;
        }
        Entry e = liveEntry(key);
        if (e == null) return null;
        Object v = e.value();
        return type.isInstance(v) ? (T) v : null;
    }

    /** Retorna el valor sin verificación de tipo. */
    public Object fetch(String key) {
        if (globalBackend != null) return globalBackend.fetch(name, key);
        Entry e = liveEntry(key);
        return e == null ? null : e.value();
    }

    /** Retorna si existe y no expiró. */
    public boolean has(String key) {
        if (globalBackend != null) return globalBackend.has(name, key);
        return liveEntry(key) != null;
    }

    private Entry liveEntry(String key) {
        Entry e = store.get(key);
        if (e == null) return null;
        if (e.isExpired()) { store.remove(key); return null; }
        e.lastAccess = System.currentTimeMillis();
        return e;
    }

    /**
     * Patrón cache-aside: retorna el valor cacheado si existe y no expiró;
     * si no, ejecuta {@code loader}, cachea el resultado con {@code ttlSeconds} y lo retorna.
     *
     * <pre>
     *   var users = JxCache.get("users")
     *       .computeIfAbsent("all", 300, () -> db.getTable("users"));
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public <T> T computeIfAbsent(String key, long ttlSeconds, java.util.concurrent.Callable<T> loader) {
        Object cached = fetch(key);
        if (cached != null) return (T) cached;
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            cached = fetch(key);
            if (cached != null) return (T) cached;
            T value = loader.call();
            if (value != null) put(key, value, ttlSeconds);
            return value;
        } catch (Exception e) {
            throw e instanceof RuntimeException r ? r : new JxException(500, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** Elimina la entrada con la clave dada. */
    public JxCache evict(String key) {
        if (globalBackend != null) { globalBackend.evict(name, key); return this; }
        store.remove(key);
        locks.remove(key);
        return this;
    }

    /** Elimina todas las entradas. */
    public JxCache clear() {
        if (globalBackend != null) { globalBackend.clear(name); return this; }
        store.clear();
        locks.clear();
        return this;
    }

    /** Número de entradas activas (puede incluir expiradas no limpiadas aún). */
    public int size() { return store.size(); }

    public String name() { return name; }

    // ── Limpieza ──────────────────────────────────────────────────────────

    private void cleanExpired() {
        store.entrySet().removeIf(e -> e.getValue().isExpired());
        locks.entrySet().removeIf(e -> !store.containsKey(e.getKey())
                && !e.getValue().isLocked()
                && !e.getValue().hasQueuedThreads());
    }

    // ── Entry interna ──────────────────────────────────────────────────────

    private static final class Entry {
        final Object value;
        final long expireAt;
        volatile long lastAccess = System.currentTimeMillis();

        Entry(Object value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        Object value() { return value; }

        boolean isExpired() {
            return expireAt > 0 && System.currentTimeMillis() > expireAt;
        }
    }
}
