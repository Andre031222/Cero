/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;

/**
 * Gestor de transacciones JDBC — thread-local, sin proxies ni bytecode.
 *
 * <p>Todas las instancias de {@link JxDB} creadas en el mismo hilo durante
 * {@code run()} comparten la misma {@link Connection}, de modo que las
 * operaciones de múltiples servicios participan en la misma transacción.
 *
 * <p>Las transacciones anidadas se aplanan: el commit solo ocurre en el
 * nivel más externo; una excepción en cualquier nivel dispara el rollback.
 *
 * <pre>
 *   // En un controlador o servicio:
 *   JxTransaction.run(() -> {
 *       orderService.create(order);      // usa JxDB internamente
 *       inventoryService.decrement(sku); // misma conexión / misma tx
 *   });
 *
 *   // Con valor de retorno:
 *   Order order = JxTransaction.call(() -> orderService.createAndReturn(dto));
 *
 *   // También disponible como anotación en métodos de controlador:
 *   &#64;JxTransactional
 *   &#64;JxPostMapping("checkout")
 *   public ActionResult checkout(&#64;JxBody OrderDto dto) { ... }
 * </pre>
 */
public final class JxTransaction {

    /** Conexión activa para el hilo actual (null fuera de una transacción). */
    static final ThreadLocal<Connection> TX = new ThreadLocal<>();

    private JxTransaction() {}

    /** Devuelve la conexión transaccional del hilo actual, o {@code null}. */
    public static Connection current() { return TX.get(); }

    /** Devuelve {@code true} si el hilo actual está dentro de una transacción. */
    public static boolean isActive() { return TX.get() != null; }

    // ── Ejecución transaccional ───────────────────────────────────────────

    /**
     * Ejecuta {@code work} dentro de una transacción.
     * Commit al finalizar sin error; rollback si lanza excepción.
     *
     * @throws JxException (500) si ocurre error de BD
     */
    public static void run(Runnable work) {
        call(() -> { work.run(); return null; });
    }

    /**
     * Ejecuta {@code work} dentro de una transacción y retorna su resultado.
     *
     * @throws JxException (500) si ocurre error de BD
     */
    public static <T> T call(Callable<T> work) {
        boolean root = TX.get() == null;
        if (!root) {
            // Transacción anidada — se aplana: participa en la conexión de la raíz.
            try { return work.call(); }
            catch (Exception e) { rethrow(e); return null; }
        }

        // Nivel raíz: abrir conexión y gestionar commit/rollback
        try (JxDB db = new JxDB()) {
            Connection conn = db.rawConnection();
            TX.set(conn);
            try {
                conn.setAutoCommit(false);
                T result = work.call();
                conn.commit();
                return result;
            } catch (Exception e) {
                safeRollback(conn);
                rethrow(e);
                return null; // unreachable
            } finally {
                // Limpieza garantizada aunque falle setAutoCommit: nunca fuga el ThreadLocal.
                safeAutoCommit(conn);
                TX.remove();
            }
        } catch (Exception e) {
            rethrow(e);
            return null;
        }
    }

    // ── Privados ──────────────────────────────────────────────────────────

    private static void safeRollback(Connection conn) {
        try { conn.rollback(); } catch (SQLException ignored) {}
    }

    private static void safeAutoCommit(Connection conn) {
        try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
    }

    private static void rethrow(Exception e) {
        if (e instanceof RuntimeException r) throw r;
        throw new JxException(500, "Transaction error: " + e.getMessage());
    }
}
