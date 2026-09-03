package cero.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Db {

    private final String source;

    private Db(String source) {
        this.source = source;
    }

    public static Db open() {
        return new Db(DataSources.DEFAULT);
    }

    public static Db open(String source) {
        return new Db(source);
    }

    public Rows query(String sql, Object... params) {
        return borrow(connection -> {
            try (PreparedStatement statement = prepare(connection, sql, params);
                 ResultSet results = statement.executeQuery()) {
                return read(results);
            }
        }, sql);
    }

    public Row one(String sql, Object... params) {
        Rows rows = query(sql, params);
        return rows.first();
    }

    public <T> List<T> query(Class<T> type, String sql, Object... params) {
        return query(sql, params).as(type);
    }

    public <T> T one(Class<T> type, String sql, Object... params) {
        Row row = one(sql, params);
        return row == null ? null : row.as(type);
    }

    public Rows queryNamed(String sql, Map<String, Object> params) {
        Named named = Named.compile(sql, params);
        return query(named.sql(), named.values());
    }

    public int exec(String sql, Object... params) {
        return borrow(connection -> {
            try (PreparedStatement statement = prepare(connection, sql, params)) {
                return statement.executeUpdate();
            }
        }, sql);
    }

    public int execNamed(String sql, Map<String, Object> params) {
        Named named = Named.compile(sql, params);
        return exec(named.sql(), named.values());
    }

    public Rows select(String table) {
        return query("SELECT * FROM " + identifier(table));
    }

    public Rows select(String table, String where, Object... params) {
        return query("SELECT * FROM " + identifier(table) + whereClause(where), params);
    }

    public Row selectOne(String table, String where, Object... params) {
        return query("SELECT * FROM " + identifier(table) + whereClause(where), params).first();
    }

    public long count(String table, String where, Object... params) {
        Row row = query("SELECT COUNT(*) AS total FROM " + identifier(table) + whereClause(where),
                params).first();
        return row == null ? 0 : row.asLong(row.columns().iterator().next());
    }

    public Page<Row> page(String table, String where, int page, int size, Object... params) {
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("página y tamaño deben ser positivos");
        }
        long total = count(table, where, params);
        Object[] extended = new Object[params.length + 2];
        System.arraycopy(params, 0, extended, 0, params.length);
        extended[params.length] = size;
        extended[params.length + 1] = (page - 1) * size;

        Rows rows = query("SELECT * FROM " + identifier(table) + whereClause(where)
                + " LIMIT ? OFFSET ?", extended);
        return new Page<>(rows, page, size, total);
    }

    public long insert(String table, Row values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no hay columnas que insertar en " + table);
        }
        StringBuilder columns = new StringBuilder();
        StringBuilder markers = new StringBuilder();
        List<Object> params = new ArrayList<>(values.size());

        for (String column : values.columns()) {
            if (!columns.isEmpty()) {
                columns.append(", ");
                markers.append(", ");
            }
            columns.append(identifier(column));
            markers.append('?');
            params.add(values.get(column));
        }

        String sql = "INSERT INTO " + identifier(table) + " (" + columns + ") VALUES (" + markers + ")";
        return borrow(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, params.toArray());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : 0L;
                }
            }
        }, sql);
    }

    public int update(String table, Row values, String where, Object... params) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no hay columnas que actualizar en " + table);
        }
        StringBuilder assignments = new StringBuilder();
        List<Object> all = new ArrayList<>(values.size() + params.length);

        for (String column : values.columns()) {
            if (!assignments.isEmpty()) {
                assignments.append(", ");
            }
            assignments.append(identifier(column)).append(" = ?");
            all.add(values.get(column));
        }
        all.addAll(List.of(params));

        return exec("UPDATE " + identifier(table) + " SET " + assignments + whereClause(where),
                all.toArray());
    }

    public int delete(String table, String where, Object... params) {
        return exec("DELETE FROM " + identifier(table) + whereClause(where), params);
    }

    public int[] insertBatch(String table, List<Row> rows) {
        if (rows.isEmpty()) {
            return new int[0];
        }
        List<String> columns = new ArrayList<>(rows.get(0).columns());
        StringBuilder names = new StringBuilder();
        StringBuilder markers = new StringBuilder();
        for (String column : columns) {
            if (!names.isEmpty()) {
                names.append(", ");
                markers.append(", ");
            }
            names.append(identifier(column));
            markers.append('?');
        }

        String sql = "INSERT INTO " + identifier(table) + " (" + names + ") VALUES (" + markers + ")";
        return borrow(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (Row row : rows) {
                    Object[] params = new Object[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        params[i] = row.get(columns.get(i));
                    }
                    bind(statement, params);
                    statement.addBatch();
                }
                return statement.executeBatch();
            }
        }, sql);
    }

    private <T> T borrow(Work<T> work, String sql) {
        Connection joined = Tx.current();
        if (joined != null) {
            return run(work, joined, sql);
        }
        Pool pool = DataSources.get(source);
        Connection connection = pool.borrow();
        try {
            return run(work, connection, sql);
        } finally {
            pool.release(connection);
        }
    }

    private static <T> T run(Work<T> work, Connection connection, String sql) {
        try {
            return work.on(connection);
        } catch (SQLException cause) {
            throw new DataException("falló la consulta: " + sql, cause);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object[] params)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        bind(statement, params);
        return statement;
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private static Rows read(ResultSet results) throws SQLException {
        ResultSetMetaData meta = results.getMetaData();
        int columns = meta.getColumnCount();
        String[] names = new String[columns];
        for (int i = 0; i < columns; i++) {
            String label = meta.getColumnLabel(i + 1);
            names[i] = label == null || label.isEmpty() ? meta.getColumnName(i + 1) : label;
        }

        List<Row> rows = new ArrayList<>();
        while (results.next()) {
            Row row = new Row();
            for (int i = 0; i < columns; i++) {
                row.put(names[i], results.getObject(i + 1));
            }
            rows.add(row);
        }
        return Rows.of(rows);
    }

    static String whereClause(String where) {
        return where == null || where.isBlank() ? "" : " WHERE " + where;
    }

    static String identifier(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("identificador vacío");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                throw new IllegalArgumentException("identificador inválido: " + name);
            }
        }
        return name;
    }

    @FunctionalInterface
    private interface Work<T> {
        T on(Connection connection) throws SQLException;
    }
}
