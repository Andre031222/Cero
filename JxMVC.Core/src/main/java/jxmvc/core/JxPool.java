/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool de conexiones JDBC ligero — cero dependencias externas.
 * Reemplaza una conexión por instancia con un pool compartido y reutilizable.
 *
 * <pre>
 * # application.properties
 * jxmvc.pool.enabled=true
 * jxmvc.pool.size=10
 * jxmvc.pool.timeout=5
 * jxmvc.pool.validationInterval=30   # segundos sin validar contra la BD (0 = validar siempre)
 * </pre>
 *
 * Si {@code jxmvc.pool.enabled=true}, {@link JxDB} lo usa automáticamente.
 * No es necesario configurarlo manualmente.
 */
public final class JxPool {

    private static final JxLogger log = JxLogger.getLogger(JxPool.class);

    private static volatile JxPool globalInstance;

    private final LinkedBlockingQueue<Connection> idle;
    private final AtomicInteger total = new AtomicInteger(0);
    private final int maxSize;
    private final int timeoutSecs;
    private final int validationIntervalSecs;
    private final String url;
    private final String user;
    private final String pass;
    private final String engine;

    private final ConcurrentHashMap<Connection, Long> lastValidated = new ConcurrentHashMap<>();

    // ── Acceso global ─────────────────────────────────────────────────────

    /** Devuelve el pool global o {@code null} si no está configurado. */
    public static JxPool global() {
        if (globalInstance == null && BaseDbResolver.poolEnabled()) {
            synchronized (JxPool.class) {
                if (globalInstance == null) {
                    globalInstance = new JxPool(
                            BaseDbResolver.url(),
                            BaseDbResolver.user(),
                            BaseDbResolver.pass(),
                            BaseDbResolver.poolSize(),
                            BaseDbResolver.poolTimeout()
                    );
                }
            }
        }
        return globalInstance;
    }

    /** Reemplaza el pool global (útil en tests o multi-tenant). */
    public static void setGlobal(JxPool pool) { globalInstance = pool; }

    public static boolean isEnabled() { return BaseDbResolver.poolEnabled(); }

    // ── Constructor ───────────────────────────────────────────────────────

    public JxPool(String url, String user, String pass, int maxSize, int timeoutSecs) {
        this(url, user, pass, maxSize, timeoutSecs,
             BaseDbResolver.propertyInt("jxmvc.pool.validationInterval", 30));
    }

    public JxPool(String url, String user, String pass, int maxSize, int timeoutSecs,
                  int validationIntervalSecs) {
        this.url         = url;
        this.user        = user;
        this.pass        = pass;
        this.maxSize     = maxSize;
        this.timeoutSecs = timeoutSecs;
        this.validationIntervalSecs = validationIntervalSecs;
        this.engine      = detectEngine(url);
        this.idle        = new LinkedBlockingQueue<>(maxSize);
        prewarm(Math.min(2, maxSize));
    }

    // ── API pública ───────────────────────────────────────────────────────

    /**
     * Obtiene una conexión del pool.
     * Espera hasta {@code timeoutSecs} segundos.
     *
     * @throws SQLException si no hay conexiones disponibles en el tiempo límite
     */
    public Connection borrow() throws SQLException {
        Connection conn;
        while ((conn = idle.poll()) != null) {
            if (isValid(conn)) return conn;
            closeQuietly(conn);
            total.decrementAndGet();
        }

        // CAS garantiza que no se supere maxSize aunque varios hilos lleguen aquí simultáneamente
        int current;
        do {
            current = total.get();
            if (current >= maxSize) break;
        } while (!total.compareAndSet(current, current + 1));
        if (current < maxSize) {
            try { return open(); }
            catch (SQLException e) { total.decrementAndGet(); throw e; }
        }

        // Esperar a que se libere una conexión
        try {
            conn = idle.poll(timeoutSecs, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Pool interrumpido esperando conexión");
        }
        if (conn == null) throw new SQLException("Pool agotado: no hay conexiones disponibles en " + timeoutSecs + "s");
        if (!isValid(conn)) {
            closeQuietly(conn);
            try { return open(); }
            catch (SQLException e) { total.decrementAndGet(); throw e; }
        }
        return conn;
    }

    /**
     * Devuelve una conexión al pool.
     * Llamar siempre después de usar — idealmente con try-with-resources via {@link PooledConnection}.
     */
    public void release(Connection conn) {
        if (conn == null) return;
        if (!isValid(conn)) { closeQuietly(conn); total.decrementAndGet(); return; }
        if (!idle.offer(conn)) { closeQuietly(conn); total.decrementAndGet(); }
    }

    public int available() { return idle.size(); }
    public int total()     { return total.get(); }
    public int maxSize()   { return maxSize; }
    public int active()    { return total.get() - idle.size(); }

    /**
     * Retorna estadísticas del pool como {@link DBRow} serializable a JSON.
     *
     * <pre>
     *   return Json(GenApi.JsonRow(JxPool.global().stats()));
     *   // → {"total":5,"idle":3,"active":2,"max":10,"engine":"PSSQL"}
     * </pre>
     */
    public DBRow stats() {
        return DBRow.of(
            "total",   total.get(),
            "idle",    idle.size(),
            "active",  active(),
            "max",     maxSize,
            "timeout", timeoutSecs,
            "engine",  engine
        );
    }

    /**
     * Valida las conexiones idle y descarta las que el servidor BD cerró por inactividad.
     * Llamar periódicamente (cada 3-5 minutos) para evitar errores en la primera petición
     * tras un periodo de inactividad prolongado.
     *
     * <p>Invocado automáticamente por el scheduler de {@code MainLxServlet}.
     */
    public void keepAlive() {
        int size = idle.size();
        for (int i = 0; i < size; i++) {
            Connection conn = idle.poll();
            if (conn == null) break;
            if (isValid(conn, true)) {
                idle.offer(conn);
            } else {
                total.decrementAndGet();
                closeQuietly(conn);
            }
        }
        // Reponer conexiones si la BD volvió tras una caída
        int target = Math.min(2, maxSize);
        while (total.get() < target && idle.size() < target) {
            int cur = total.get();
            if (cur >= maxSize) break;
            if (!total.compareAndSet(cur, cur + 1)) continue;
            try { idle.offer(open()); }
            catch (SQLException e) {
                total.decrementAndGet();
                log.warn("keepAlive: no se pudo reponer conexión: {}", e.getMessage());
                break;
            }
        }
    }

    /**
     * Cierra todas las conexiones idle del pool.
     * Las conexiones prestadas (en uso) no se cierran: el caller debe liberarlas
     * con {@link #release(Connection)} o cerrarlas por su cuenta.
     */
    public void shutdown() {
        Connection conn;
        while ((conn = idle.poll()) != null) closeQuietly(conn);
        lastValidated.clear();
        total.set(0);
    }

    // ── Privados ──────────────────────────────────────────────────────────

    private void prewarm(int count) {
        for (int i = 0; i < count; i++) {
            try {
                Connection conn = open();
                total.incrementAndGet();
                idle.offer(conn);
            } catch (SQLException e) {
                log.warn("Pool prewarm: no se pudo abrir conexión ({}). El pool iniciará vacío.", e.getMessage());
                break;
            }
        }
    }

    private Connection open() throws SQLException {
        loadDriver();
        Connection conn = switch (engine) {
            case "MSSQL" -> DriverManager.getConnection(url);
            default      -> DriverManager.getConnection(url, user, pass);
        };
        lastValidated.put(conn, System.currentTimeMillis());
        return conn;
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) return;
        lastValidated.remove(conn);
        try { conn.close(); } catch (SQLException ignored) {}
    }

    private void loadDriver() throws SQLException {
        String cls = switch (engine) {
            case "PSSQL" -> "org.postgresql.Driver";
            case "MySQL" -> "com.mysql.cj.jdbc.Driver";
            case "MSSQL" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> throw new SQLException("Motor no reconocido: " + url);
        };
        try { Class.forName(cls); }
        catch (ClassNotFoundException e) { throw new SQLException("Driver no encontrado: " + cls, e); }
    }

    private boolean isValid(Connection conn) { return isValid(conn, false); }

    private boolean isValid(Connection conn, boolean force) {
        try {
            if (conn == null || conn.isClosed()) return false;
            if (!force && validationIntervalSecs > 0) {
                Long last = lastValidated.get(conn);
                if (last != null && System.currentTimeMillis() - last < validationIntervalSecs * 1000L)
                    return true;
            }
            boolean ok = conn.isValid(1);
            if (ok) lastValidated.put(conn, System.currentTimeMillis());
            else    lastValidated.remove(conn);
            return ok;
        } catch (SQLException e) {
            return false;
        }
    }

    private String detectEngine(String u) {
        if (u == null) return "Unknown";
        String l = u.toLowerCase();
        if (l.contains(":postgresql:")) return "PSSQL";
        if (l.contains(":sqlserver:"))  return "MSSQL";
        if (l.contains(":mysql:"))      return "MySQL";
        return "Unknown";
    }
}
