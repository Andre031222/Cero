package lux.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Pool implements AutoCloseable {

    private final String url;
    private final String user;
    private final String password;
    private final int maxSize;
    private final long borrowTimeoutMillis;
    private final boolean validate;

    private final BlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger borrowed = new AtomicInteger();

    private volatile boolean open = true;

    private Pool(Builder builder) {
        url = builder.url;
        user = builder.user;
        password = builder.password;
        maxSize = builder.maxSize;
        borrowTimeoutMillis = builder.borrowTimeoutMillis;
        validate = builder.validate;
        idle = new ArrayBlockingQueue<>(builder.maxSize);
    }

    public static Builder to(String url) {
        return new Builder(url);
    }

    public static Pool of(String url, String user, String password) {
        return new Builder(url).credentials(user, password).build();
    }

    public Connection borrow() {
        if (!open) {
            throw new DataException("el pool está cerrado");
        }
        Connection reused = idle.poll();
        while (reused != null) {
            if (usable(reused)) {
                borrowed.incrementAndGet();
                return reused;
            }
            discard(reused);
            reused = idle.poll();
        }
        if (created.get() < maxSize && created.incrementAndGet() <= maxSize) {
            borrowed.incrementAndGet();
            return connect();
        }
        created.decrementAndGet();
        return waitForOne();
    }

    public void release(Connection connection) {
        if (connection == null) {
            return;
        }
        borrowed.decrementAndGet();
        if (!open || !usable(connection) || !idle.offer(connection)) {
            discard(connection);
        }
    }

    public int available() {
        return idle.size();
    }

    public int active() {
        return borrowed.get();
    }

    public int size() {
        return created.get();
    }

    public int maxSize() {
        return maxSize;
    }

    public boolean open() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        Connection connection;
        while ((connection = idle.poll()) != null) {
            discard(connection);
        }
    }

    private Connection waitForOne() {
        try {
            Connection connection = idle.poll(borrowTimeoutMillis, TimeUnit.MILLISECONDS);
            if (connection == null) {
                throw new DataException("no hay conexiones libres tras " + borrowTimeoutMillis + " ms"
                        + " (máximo " + maxSize + ")");
            }
            if (!usable(connection)) {
                discard(connection);
                return borrow();
            }
            borrowed.incrementAndGet();
            return connection;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DataException("interrumpido esperando una conexión", interrupted);
        }
    }

    private Connection connect() {
        try {
            return user == null
                    ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, password);
        } catch (SQLException cause) {
            created.decrementAndGet();
            borrowed.decrementAndGet();
            throw new DataException("no se pudo conectar a " + url, cause);
        }
    }

    private boolean usable(Connection connection) {
        try {
            if (connection.isClosed()) {
                return false;
            }
            return !validate || connection.isValid(1);
        } catch (SQLException cause) {
            return false;
        }
    }

    private void discard(Connection connection) {
        created.decrementAndGet();
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    public static final class Builder {

        private final String url;
        private String user;
        private String password;
        private int maxSize = 10;
        private long borrowTimeoutMillis = 5_000;
        private boolean validate = true;

        private Builder(String url) {
            this.url = url;
        }

        public Builder credentials(String user, String password) {
            this.user = user;
            this.password = password;
            return this;
        }

        public Builder maxSize(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxSize debe ser al menos 1");
            }
            maxSize = value;
            return this;
        }

        public Builder borrowTimeoutMillis(long value) {
            borrowTimeoutMillis = value;
            return this;
        }

        public Builder validate(boolean value) {
            validate = value;
            return this;
        }

        public Pool build() {
            return new Pool(this);
        }
    }
}
