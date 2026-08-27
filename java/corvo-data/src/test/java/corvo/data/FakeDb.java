package corvo.data;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class FakeDb implements java.sql.Driver {

    public static final String URL = "jdbc:luxtest:memoria";

    static final List<Executed> log = Collections.synchronizedList(new ArrayList<>());
    static final Deque<List<Map<String, Object>>> results = new ArrayDeque<>();
    static final Deque<Integer> updates = new ArrayDeque<>();

    static final AtomicInteger opened = new AtomicInteger();
    static final AtomicInteger closed = new AtomicInteger();
    static final AtomicInteger validated = new AtomicInteger();
    static final AtomicInteger commits = new AtomicInteger();
    static final AtomicInteger rollbacks = new AtomicInteger();
    static final AtomicLong nextKey = new AtomicLong(1);

    static volatile boolean failNextConnect;
    static volatile boolean autoCommit = true;

    static {
        try {
            DriverManager.registerDriver(new FakeDb());
        } catch (SQLException ignored) {
        }
    }

    public record Executed(String sql, List<Object> params) {
    }

    static void reset() {
        log.clear();
        results.clear();
        updates.clear();
        opened.set(0);
        closed.set(0);
        validated.set(0);
        commits.set(0);
        rollbacks.set(0);
        nextKey.set(1);
        failNextConnect = false;
        autoCommit = true;
    }

    static void willReturn(List<Map<String, Object>> rows) {
        results.add(rows);
    }

    static void willReturn(Map<String, Object>... rows) {
        results.add(List.of(rows));
    }

    static void willUpdate(int count) {
        updates.add(count);
    }

    static Executed lastQuery() {
        synchronized (log) {
            return log.isEmpty() ? null : log.get(log.size() - 1);
        }
    }

    static Executed queryAt(int index) {
        synchronized (log) {
            return log.get(index);
        }
    }

    static int queries() {
        return log.size();
    }

    static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return row;
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:luxtest:");
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        if (failNextConnect) {
            throw new SQLException("conexión rechazada a propósito");
        }
        opened.incrementAndGet();
        return connection();
    }

    private static Connection connection() {
        boolean[] shut = {false};
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement((String) args[0]);
            case "createStatement" -> statement("");
            case "isClosed" -> shut[0];
            case "isValid" -> {
                validated.incrementAndGet();
                yield !shut[0];
            }
            case "close" -> {
                if (!shut[0]) {
                    shut[0] = true;
                    closed.incrementAndGet();
                }
                yield null;
            }
            case "setAutoCommit" -> {
                autoCommit = (boolean) args[0];
                yield null;
            }
            case "getAutoCommit" -> autoCommit;
            case "commit" -> {
                commits.incrementAndGet();
                yield null;
            }
            case "rollback" -> {
                rollbacks.incrementAndGet();
                yield null;
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "FakeConnection@" + System.identityHashCode(proxy);
            default -> defaultValue(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private static PreparedStatement statement(String sql) {
        List<Object> params = new ArrayList<>();
        List<Integer> batch = new ArrayList<>();

        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "setObject", "setString", "setInt", "setLong" -> {
                int index = (int) args[0] - 1;
                while (params.size() <= index) {
                    params.add(null);
                }
                params.set(index, args[1]);
                yield null;
            }
            case "executeQuery" -> {
                record(sql, params);
                yield resultSet(results.isEmpty() ? List.of() : results.poll());
            }
            case "executeUpdate" -> {
                record(sql, params);
                yield updates.isEmpty() ? 1 : updates.poll();
            }
            case "addBatch" -> {
                batch.add(1);
                record(sql, params);
                params.clear();
                yield null;
            }
            case "executeBatch" -> {
                int[] counts = new int[batch.size()];
                java.util.Arrays.fill(counts, 1);
                yield counts;
            }
            case "getGeneratedKeys" -> resultSet(List.of(row("id", nextKey.getAndIncrement())));
            case "close" -> null;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (PreparedStatement) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, handler);
    }

    private static void record(String sql, List<Object> params) {
        log.add(new Executed(sql, List.copyOf(params)));
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] cursor = {-1};
        List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());

        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++cursor[0] < rows.size();
            case "getMetaData" -> metaData(columns);
            case "getObject" -> value(rows, columns, cursor[0], args[0]);
            case "getLong" -> {
                Object found = value(rows, columns, cursor[0], args[0]);
                yield found instanceof Number number ? number.longValue() : 0L;
            }
            case "getString" -> String.valueOf(value(rows, columns, cursor[0], args[0]));
            case "close" -> null;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (ResultSet) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, handler);
    }

    private static Object value(List<Map<String, Object>> rows, List<String> columns, int at, Object key) {
        if (at < 0 || at >= rows.size()) {
            return null;
        }
        Map<String, Object> row = rows.get(at);
        if (key instanceof Integer index) {
            return index >= 1 && index <= columns.size() ? row.get(columns.get(index - 1)) : null;
        }
        return row.get(String.valueOf(key));
    }

    private static ResultSetMetaData metaData(List<String> columns) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> columns.size();
            case "getColumnLabel", "getColumnName" -> columns.get((int) args[0] - 1);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (ResultSetMetaData) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }
}
