package lux.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;

public final class Tx {

    private static final ThreadLocal<Active> CURRENT = new ThreadLocal<>();

    private Tx() {
    }

    public static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    public static <T> T call(Callable<T> work) {
        return call(DataSources.DEFAULT, work);
    }

    public static <T> T call(String source, Callable<T> work) {
        Active existing = CURRENT.get();
        if (existing != null) {
            return join(work);
        }

        Pool pool = DataSources.get(source);
        Connection connection = pool.borrow();
        Active active = new Active(pool, connection);
        CURRENT.set(active);
        try {
            connection.setAutoCommit(false);
            T outcome = work.call();
            connection.commit();
            return outcome;
        } catch (Exception failure) {
            rollback(connection);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new DataException("la transacción falló", failure);
        } finally {
            CURRENT.remove();
            restore(connection);
            pool.release(connection);
        }
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    static Connection current() {
        Active active = CURRENT.get();
        return active == null ? null : active.connection();
    }

    private static <T> T join(Callable<T> work) {
        try {
            return work.call();
        } catch (Exception failure) {
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new DataException("la transacción anidada falló", failure);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restore(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private record Active(Pool pool, Connection connection) {
    }
}
