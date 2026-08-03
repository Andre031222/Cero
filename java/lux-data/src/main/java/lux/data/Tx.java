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
        Active active = new Active(connection);
        CURRENT.set(active);
        try {
            connection.setAutoCommit(false);
            T outcome = work.call();
            // Si una transacción anidada falló y quien la llamó se comió la excepción, la de
            // fuera NO puede confirmar: llevaría dentro el trabajo a medias de la interna.
            if (active.condenada()) {
                rollback(connection);
                throw new DataException(
                        "la transacción se deshace: una anidada falló y su fallo se capturó");
            }
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
            // La anidada se une a la de fuera, así que no puede deshacer solo lo suyo. Lo que sí
            // puede es condenar a la de fuera, y así el fallo no se pierde aunque se capture.
            Active active = CURRENT.get();
            if (active != null) {
                active.condenar();
            }
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new DataException("la transacción anidada falló", failure);
        }
    }

    /** Marca la transacción en curso para que no pueda confirmarse. */
    public static void setRollbackOnly() {
        Active active = CURRENT.get();
        if (active == null) {
            throw new IllegalStateException("no hay transacción en curso");
        }
        active.condenar();
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

    private static final class Active {

        private final Connection connection;
        private boolean condenada;

        Active(Connection connection) {
            this.connection = connection;
        }

        Connection connection() {
            return connection;
        }

        void condenar() {
            condenada = true;
        }

        boolean condenada() {
            return condenada;
        }
    }
}
