/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Capa de acceso a datos JDBC — PostgreSQL, MySQL y SQL Server.
 * Implementa {@link AutoCloseable}: usar con try-with-resources.
 *
 * <pre>
 *   // Conexión desde application.properties
 *   try (JxDB db = new JxDB()) {
 *       DBRowSet rows = db.getTable("users", "status = ?", "active");
 *       long id = db.insert("users", DBRow.of("name","Ana","age",25));
 *       db.update("users", DBRow.of("name","Ana M."), "id = ?", id);
 *       db.delete("users", "id = ?", id);
 *   }
 *
 *   // Conexión personalizada
 *   JxDB db = new JxDB("postgresql://host:5432/db", "user", "pass");
 *
 *   // Query SQL directo
 *   DBRowSet r = db.query("SELECT * FROM orders WHERE total > ? ORDER BY date DESC", 100);
 * </pre>
 */
public class JxDB implements AutoCloseable {

    private Connection conn;
    private boolean    borrowedFromPool;
    private final String connUrl;
    private final String user;
    private final String pass;
    private final String engine;
    private String lastError;

    // ── Constructores ─────────────────────────────────────────────────────

    /** Usa la conexión configurada en {@code application.properties}. */
    public JxDB() {
        this.connUrl = BaseDbResolver.url();
        this.user    = BaseDbResolver.user();
        this.pass    = BaseDbResolver.pass();
        this.engine  = detectEngine(this.connUrl);
    }

    /**
     * Datasource por nombre (registrado con {@link JxDataSourceRegistry}) o URL JDBC directa.
     * Si {@code nameOrUrl} coincide con un datasource registrado se usa ese;
     * si no, se trata como URL JDBC.
     *
     * <pre>
     *   // Datasource registrado:
     *   try (JxDB db = new JxDB("reporting")) { ... }
     *
     *   // URL directa:
     *   try (JxDB db = new JxDB("jdbc:postgresql://host/db")) { ... }
     * </pre>
     */
    public JxDB(String nameOrUrl) {
        JxDataSourceRegistry.DSConfig ds = JxDataSourceRegistry.get(nameOrUrl);
        if (ds != null) {
            this.connUrl = toJdbc(ds.url());
            this.user    = ds.user();
            this.pass    = ds.pass();
        } else {
            this.connUrl = toJdbc(nameOrUrl);
            this.user    = null;
            this.pass    = null;
        }
        this.engine = detectEngine(this.connUrl);
    }

    /** URL + credenciales explícitas. */
    public JxDB(String url, String user, String pass) {
        this.connUrl = toJdbc(url);
        this.user    = user;
        this.pass    = pass;
        this.engine  = detectEngine(this.connUrl);
    }

    // ── Estado ────────────────────────────────────────────────────────────

    public boolean isConnected() {
        return Boolean.TRUE.equals(withConn(c -> c.isValid(2), Boolean.FALSE));
    }

    public String getError()    { return lastError; }
    public String getConnUrl()  { return connUrl; }
    public String getEngine()   { return engine; }

    @Override
    public void close() {
        if (conn == null) return;
        // La conexión administrada por JxTransaction (ThreadLocal) la libera JxTransaction.
        if (conn == JxTransaction.current()) { conn = null; return; }
        releaseRetained();
    }

    // ── SELECT ────────────────────────────────────────────────────────────

    /** {@code SELECT * FROM table} */
    public DBRowSet getTable(String table) {
        return exec_("SELECT * FROM " + qt(table));
    }

    /**
     * {@code SELECT * FROM table WHERE filter}
     * El filtro puede contener {@code ?} y los valores se pasan como params.
     *
     * <pre>
     *   db.getTable("users", "status = ?", "active")
     *   db.getTable("users", "age > ? AND city = ?", 18, "Lima")
     *   db.getTable("orders", "ORDER BY date DESC")   // sin WHERE, solo cláusula
     * </pre>
     */
    public DBRowSet getTable(String table, String filter, Object... params) {
        if (filter == null || filter.isBlank()) return getTable(table);
        String upper = filter.stripLeading().toUpperCase();
        if (upper.startsWith("ORDER") || upper.startsWith("GROUP") || upper.startsWith("LIMIT")) {
            return exec_("SELECT * FROM " + qt(table) + " " + filter);
        }
        return exec_("SELECT * FROM " + qt(table) + " WHERE " + filter, params);
    }

    /**
     * {@code SELECT * FROM table WHERE filter clause}
     *
     * <pre>
     *   db.getTable("orders", "user_id = ?", "ORDER BY date DESC", userId)
     * </pre>
     */
    public DBRowSet getTable(String table, String filter, String clause, Object... params) {
        String sql = "SELECT * FROM " + qt(table);
        if (filter != null && !filter.isBlank()) sql += " WHERE " + filter;
        if (clause != null && !clause.isBlank()) sql += " " + clause;
        return exec_(sql, params);
    }

    /**
     * Devuelve la primera fila que cumpla el filtro, o {@code null}.
     *
     * <pre>
     *   DBRow user = db.getRow("users", "email = ?", email);
     * </pre>
     */
    public DBRow getRow(String table, String filter, Object... params) {
        String sql = "MSSQL".equals(engine)
            ? "SELECT TOP 1 * FROM " + qt(table) + " WHERE " + filter
            : "SELECT * FROM "       + qt(table) + " WHERE " + filter + " LIMIT 1";
        DBRowSet rs = exec_(sql, params);
        return rs.isEmpty() ? null : rs.get(0);
    }

    /**
     * Llama a una función de tabla PostgreSQL.
     *
     * <pre>
     *   DBRowSet result = db.getFuncTable("fn_reporte", userId, year);
     * </pre>
     */
    public DBRowSet getFuncTable(String funcName, Object... args) {
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < args.length; i++) { if (i > 0) ph.append(','); ph.append('?'); }
        return exec_("SELECT * FROM " + qt(funcName) + "(" + ph + ")", args);
    }

    /**
     * SQL SELECT libre con parámetros.
     *
     * <pre>
     *   DBRowSet rows = db.query("SELECT id, name FROM users WHERE age > ? ORDER BY name", 18);
     * </pre>
     */
    public DBRowSet query(String sql, Object... params) { return exec_(sql, params); }

    /**
     * Devuelve la primera fila de un SQL SELECT libre, o {@code null}.
     *
     * <pre>
     *   DBRow row = db.queryRow("SELECT * FROM users WHERE id = ?", id);
     * </pre>
     */
    public DBRow queryRow(String sql, Object... params) {
        DBRowSet rs = exec_(sql, params);
        return rs.isEmpty() ? null : rs.get(0);
    }

    // ── INSERT ────────────────────────────────────────────────────────────

    /**
     * Inserta una fila y retorna el ID generado (0 si no aplica).
     *
     * <pre>
     *   long id = db.insert("users", DBRow.of("name","Ana","age",25));
     * </pre>
     */
    public long insert(String table, DBRow fields) {
        lastError = "";
        if (fields == null || fields.isEmpty()) return 0;

        StringBuilder cols = new StringBuilder();
        StringBuilder ph   = new StringBuilder();
        for (String k : fields.keySet()) {
            if (cols.length() > 0) { cols.append(", "); ph.append(", "); }
            cols.append(qc(k));
            ph.append('?');
        }
        String sql = "INSERT INTO " + qt(table) + " (" + cols + ") VALUES (" + ph + ")";

        return withConn(c -> {
            try (PreparedStatement stmt = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                for (String k : fields.keySet()) stmt.setObject(i++, fields.get(k));
                if (stmt.executeUpdate() > 0) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) return rs.getLong(1);
                    }
                }
            }
            return 0L;
        }, 0L);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────

    /**
     * Actualiza filas que cumplan la condición.
     *
     * <pre>
     *   db.update("users", DBRow.of("name","Pedro"), "id = ?", userId);
     * </pre>
     */
    public void update(String table, DBRow fields, String condition, Object... condParams) {
        lastError = "";
        if (fields == null || fields.isEmpty()) return;

        StringBuilder set = new StringBuilder();
        for (String k : fields.keySet()) {
            if (set.length() > 0) set.append(", ");
            set.append(qc(k)).append(" = ?");
        }
        String base = "UPDATE " + qt(table) + " SET " + set;
        final String sql = (condition != null && !condition.isBlank())
                ? base + " WHERE " + condition : base;

        withConn(c -> {
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                int i = 1;
                for (String k : fields.keySet()) stmt.setObject(i++, fields.get(k));
                for (Object p : condParams)       stmt.setObject(i++, p);
                return stmt.executeUpdate();
            }
        }, -1);
    }

    /** Versión con {@link Map} en lugar de {@link DBRow}. */
    public void update(String table, Map<String, Object> fields, String condition, Object... condParams) {
        DBRow row = new DBRow();
        fields.forEach(row::add);
        update(table, row, condition, condParams);
    }

    // ── DELETE ────────────────────────────────────────────────────────────

    /**
     * Elimina filas que cumplan la condición.
     *
     * <pre>
     *   db.delete("users", "id = ?", userId);
     *   db.delete("logs",  "created_at < ?", cutoffDate);
     * </pre>
     */
    public void delete(String table, String condition, Object... params) {
        lastError = "";
        String base = "DELETE FROM " + qt(table);
        final String sql = (condition != null && !condition.isBlank())
                ? base + " WHERE " + condition : base;
        withConn(c -> {
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) stmt.setObject(i + 1, params[i]);
                return stmt.executeUpdate();
            }
        }, -1);
    }

    // ── Parámetros con nombre (:param) ────────────────────────────────────

    /**
     * SELECT con parámetros nombrados {@code :name} en lugar de {@code ?}.
     *
     * <pre>
     *   DBRowSet rows = db.queryNamed(
     *       "SELECT * FROM users WHERE name = :name AND age > :age",
     *       Map.of("name", "Ana", "age", 18)
     *   );
     * </pre>
     */
    public DBRowSet queryNamed(String sql, Map<String, Object> params) {
        NamedQuery nq = parseNamed(sql, params);
        return exec_(nq.sql, nq.params);
    }

    /**
     * SELECT una fila con parámetros nombrados.
     *
     * <pre>
     *   DBRow user = db.queryRowNamed(
     *       "SELECT * FROM users WHERE email = :email",
     *       Map.of("email", "ana@mail.com")
     *   );
     * </pre>
     */
    public DBRow queryRowNamed(String sql, Map<String, Object> params) {
        DBRowSet rs = queryNamed(sql, params);
        return rs.isEmpty() ? null : rs.get(0);
    }

    /**
     * DML con parámetros nombrados.
     *
     * <pre>
     *   db.execNamed(
     *       "UPDATE users SET name = :name WHERE id = :id",
     *       Map.of("name", "Pedro", "id", 5)
     *   );
     * </pre>
     */
    public int execNamed(String sql, Map<String, Object> params) {
        NamedQuery nq = parseNamed(sql, params);
        return exec(nq.sql, nq.params);
    }

    // ── INSERT por lote ───────────────────────────────────────────────────

    /**
     * Inserta múltiples filas en una sola operación batch.
     * Retorna el array de filas afectadas por cada INSERT.
     *
     * <pre>
     *   db.insertBatch("users", List.of(
     *       DBRow.of("nombre", "Ana",   "email", "ana@mail.com"),
     *       DBRow.of("nombre", "Pedro", "email", "pedro@mail.com"),
     *       DBRow.of("nombre", "Luis",  "email", "luis@mail.com")
     *   ));
     * </pre>
     */
    public int[] insertBatch(String table, List<DBRow> rows) {
        lastError = "";
        if (rows == null || rows.isEmpty()) return new int[0];
        DBRow first = rows.get(0);
        if (first.isEmpty()) return new int[0];

        StringBuilder cols = new StringBuilder();
        StringBuilder ph   = new StringBuilder();
        for (String k : first.KeySet()) {
            if (cols.length() > 0) { cols.append(", "); ph.append(", "); }
            cols.append(qc(k)); ph.append('?');
        }
        String sql = "INSERT INTO " + qt(table) + " (" + cols + ") VALUES (" + ph + ")";

        return withConn(c -> {
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                for (DBRow row : rows) {
                    int i = 1;
                    for (String k : first.KeySet()) stmt.setObject(i++, row.Get(k));
                    stmt.addBatch();
                }
                return stmt.executeBatch();
            }
        }, new int[0]);
    }

    /**
     * INSERT o UPDATE en lote según el campo PK: si pk es 0/null hace INSERT, si no UPDATE.
     *
     * <pre>
     *   db.upsertBatch("productos", "id", productos);
     * </pre>
     */
    public void upsertBatch(String table, String pkField, List<DBRow> rows) {
        List<DBRow> inserts = new ArrayList<>();
        List<DBRow> updates = new ArrayList<>();
        for (DBRow row : rows) {
            Object pkVal = row.Get(pkField);
            boolean isNew = pkVal == null
                || (pkVal instanceof Number n && n.longValue() == 0)
                || "0".equals(String.valueOf(pkVal));
            if (isNew) inserts.add(row); else updates.add(row);
        }
        if (!inserts.isEmpty()) insertBatch(table, inserts);
        for (DBRow row : updates) {
            Object pkVal = row.Get(pkField);
            DBRow fields = new DBRow();
            for (String k : row.KeySet()) { if (!k.equals(pkField)) fields.Add(k, row.Get(k)); }
            update(table, fields, pkField + " = ?", pkVal);
        }
    }

    // ── Paginación ────────────────────────────────────────────────────────

    /**
     * SELECT paginado con COUNT total.
     *
     * <pre>
     *   JxPageResult page = db.getTablePaged("users", "status = ?", 0, 20, "active");
     *   // page.getData()       → DBRowSet con los 20 registros
     *   // page.getTotal()      → 150 (total sin paginar)
     *   // page.getTotalPages() → 8
     * </pre>
     */
    public JxPageResult getTablePaged(String table, String filter, int page, int size, Object... params) {
        page = Math.max(0, page);
        size = Math.max(1, size);
        int offset = page * size;

        String where    = (filter != null && !filter.isBlank()) ? " WHERE " + filter : "";
        String qtTable  = qt(table);
        String countSql = "SELECT COUNT(*) AS _cnt FROM " + qtTable + where;

        String dataSql;
        if ("MSSQL".equals(engine)) {
            // SQL Server: requiere ORDER BY para OFFSET/FETCH
            dataSql = "SELECT * FROM " + qtTable + where
                    + " ORDER BY (SELECT NULL)"
                    + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
        } else {
            // PostgreSQL / MySQL
            dataSql = "SELECT * FROM " + qtTable + where
                    + " LIMIT " + size + " OFFSET " + offset;
        }

        DBRow countRow = queryRow(countSql, params);
        long total = 0;
        if (countRow != null) {
            Object cnt = countRow.get("_cnt");
            if (cnt instanceof Number n) total = n.longValue();
        }
        DBRowSet data = exec_(dataSql, params);
        return JxPageResult.of(data, page, size, total);
    }

    // ── Transacciones ─────────────────────────────────────────────────────

    /**
     * Ejecuta {@code work} en una transacción JDBC.
     * Alternativa programática a {@code @JxTransactional}.
     *
     * <pre>
     *   db.transaction(() -> {
     *       db.insert("orders",    DBRow.of("user_id", 1, "total", 99.9));
     *       db.update("inventory", DBRow.of("stock", 9), "sku = ?", "PROD-1");
     *   });
     * </pre>
     */
    public void transaction(Runnable work) {
        JxTransaction.run(work);
    }

    /** Abre una transacción manual sobre una conexión retenida por esta instancia. */
    public void beginTransaction() {
        if (JxTransaction.current() != null) return;
        try {
            if (conn == null) conn = acquire();
            conn.setAutoCommit(false);
        } catch (Exception e) { lastError = e.getMessage(); }
    }

    public void commit() {
        if (conn == null) return;
        try { conn.commit(); conn.setAutoCommit(true); }
        catch (Exception e) { lastError = e.getMessage(); }
        finally { releaseRetained(); }
    }

    public void rollback() {
        if (conn == null) return;
        try { conn.rollback(); conn.setAutoCommit(true); }
        catch (Exception e) { lastError = e.getMessage(); }
        finally { releaseRetained(); }
    }

    // ── DML crudo ─────────────────────────────────────────────────────────

    /**
     * Ejecuta cualquier sentencia DML (INSERT/UPDATE/DELETE/CREATE...).
     * Retorna el número de filas afectadas o -1 si hubo error.
     *
     * <pre>
     *   db.exec("UPDATE config SET value = ? WHERE key = ?", "dark", "theme");
     * </pre>
     */
    public int exec(String sql, Object... params) {
        return withConn(c -> {
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) stmt.setObject(i + 1, params[i]);
                return stmt.executeUpdate();
            }
        }, -1);
    }

    // ── Privados ──────────────────────────────────────────────────────────

    private DBRowSet exec_(String sql, Object... params) {
        return withConn(c -> {
            DBRowSet result = new DBRowSet();
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) stmt.setObject(i + 1, params[i]);
                try (ResultSet rs = stmt.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        DBRow row = new DBRow();
                        for (int i = 1; i <= colCount; i++) row.add(meta.getColumnName(i), rs.getObject(i));
                        result.add(row);
                    }
                }
            }
            return result;
        }, new DBRowSet());
    }

    @FunctionalInterface
    private interface ConnUse<T> { T apply(Connection c) throws SQLException; }

    /**
     * Ejecuta {@code body} con una conexión.
     * <ul>
     *   <li>Dentro de una transacción (ThreadLocal de {@link JxTransaction} o transacción
     *       manual de esta instancia) reutiliza esa conexión y no la libera.
     *   <li>Fuera de transacción toma una conexión efímera del pool (o directa), la usa
     *       y la libera de inmediato — sin fugas ni estado compartido entre hilos.
     * </ul>
     */
    private <T> T withConn(ConnUse<T> body, T onError) {
        lastError = "";
        Connection managed = activeConn();
        if (managed != null) {
            try { return body.apply(managed); }
            catch (SQLException e) { lastError = e.getMessage(); return onError; }
        }
        JxPool pool = JxPool.global();
        Connection c = null;
        boolean pooled = false;
        try {
            if (pool != null) { c = pool.borrow(); pooled = true; }
            else             { c = connect(); }
            return body.apply(c);
        } catch (SQLException e) {
            lastError = e.getMessage();
            return onError;
        } finally {
            if (c != null) {
                if (pooled) pool.release(c);
                else        closeQuietly(c);
            }
        }
    }

    /** Conexión de transacción activa (ThreadLocal global o transacción manual), o {@code null}. */
    private Connection activeConn() {
        Connection tx = JxTransaction.current();
        return tx != null ? tx : conn;
    }

    private Connection acquire() throws SQLException {
        JxPool pool = JxPool.global();
        if (pool != null) { borrowedFromPool = true; return pool.borrow(); }
        borrowedFromPool = false;
        return connect();
    }

    private void releaseRetained() {
        if (conn == null) return;
        Connection c = conn;
        conn = null;
        try { if (!c.getAutoCommit()) { c.rollback(); c.setAutoCommit(true); } }
        catch (SQLException ignored) {}
        if (borrowedFromPool) {
            JxPool pool = JxPool.global();
            if (pool != null) { pool.release(c); return; }
        }
        closeQuietly(c);
    }

    private void closeQuietly(Connection c) {
        try { c.close(); } catch (SQLException ignored) {}
    }

    /** Para uso exclusivo de {@link JxTransaction} — no llamar externamente. */
    Connection rawConnection() throws SQLException {
        if (conn == null) conn = acquire();
        return conn;
    }

    private Connection connect() throws SQLException {
        return switch (engine) {
            case "PSSQL" -> {
                loadDriver("org.postgresql.Driver");
                yield DriverManager.getConnection(connUrl, user, pass);
            }
            case "MySQL" -> {
                try { loadDriver("com.mysql.cj.jdbc.Driver"); }
                catch (SQLException ex) { loadDriver("com.mysql.jdbc.Driver"); }
                yield DriverManager.getConnection(connUrl, user, pass);
            }
            case "MSSQL" -> {
                loadDriver("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                yield DriverManager.getConnection(connUrl);
            }
            default -> throw new SQLException("Motor no reconocido para: " + connUrl);
        };
    }

    private void loadDriver(String cls) throws SQLException {
        try { Class.forName(cls); }
        catch (ClassNotFoundException e) { throw new SQLException("Driver no encontrado: " + cls, e); }
    }

    private String qt(String name) { return q(name); }
    private String qc(String name) { return q(name); }

    private String q(String name) {
        String safe = safeId(name);
        return switch (engine) {
            case "PSSQL" -> "\"" + safe + "\"";
            case "MySQL" -> "`"  + safe + "`";
            case "MSSQL" -> "["  + safe + "]";
            default      -> safe;
        };
    }

    private String safeId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Identificador SQL vacío");
        String clean = id.trim().replaceAll("^\"|\"$", "");
        // Permite schema.tabla (punto como separador) y nombres con guión bajo
        if (!clean.matches("[A-Za-z_][A-Za-z0-9_.]*"))
            throw new IllegalArgumentException("Identificador SQL inválido: " + id);
        // Verificar cada parte del identificador (antes y después del punto)
        for (String part : clean.split("\\.")) {
            String lower = part.toLowerCase();
            for (String w : RESERVED) {
                if (w.equals(lower)) throw new IllegalArgumentException("Palabra reservada: " + part);
            }
        }
        return clean;
    }

    // ── Named parameters helper ───────────────────────────────────────────

    private record NamedQuery(String sql, Object[] params) {}

    private static final Pattern NAMED_PARAM = Pattern.compile(":([a-zA-Z][a-zA-Z0-9_]*)");

    private static NamedQuery parseNamed(String sql, Map<String, Object> named) {
        List<Object> values = new ArrayList<>();
        Matcher m = NAMED_PARAM.matcher(sql);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(sql, last, m.start()).append('?');
            values.add(named != null ? named.get(m.group(1)) : null);
            last = m.end();
        }
        sb.append(sql, last, sql.length());
        return new NamedQuery(sb.toString(), values.toArray());
    }

    private static final String[] RESERVED = {
        "select","insert","update","delete","drop","alter",
        "create","truncate","union","where","from","join"
    };

    private String detectEngine(String url) {
        if (url == null) return "Unknown";
        String lower = url.toLowerCase();
        if (lower.contains(":postgresql:")) return "PSSQL";
        if (lower.contains(":sqlserver:"))  return "MSSQL";
        if (lower.contains(":mysql:"))      return "MySQL";
        return "Unknown";
    }

    private String toJdbc(String url) {
        return (url != null && !url.startsWith("jdbc:")) ? "jdbc:" + url : url;
    }

    // ── API PascalCase (estilo JxMVC) ────────────────────────────────────
    // Equivalente completo a los métodos camelCase — usar el que prefieras.

    public boolean  IsConnected()                                          { return isConnected(); }

    /** {@code SELECT * FROM table} */
    public DBRowSet GetTable(String table)                                 { return getTable(table); }

    /** {@code SELECT * FROM table WHERE filter} — acepta {@code ?} como placeholder. */
    public DBRowSet GetTable(String table, String filter, Object... params){ return getTable(table, filter, params); }

    /** {@code SELECT * FROM table WHERE filter clause} */
    public DBRowSet GetTable(String table, String filter, String clause, Object... params) { return getTable(table, filter, clause, params); }

    /** Primera fila que cumpla el filtro, o {@code null}. Acepta {@code ?} como placeholder. */
    public DBRow    GetRow(String table, String filter, Object... params)  { return getRow(table, filter, params); }

    /** Llama a una función de tabla (PostgreSQL). */
    public DBRowSet GetFuncTable(String funcName, Object... args)          { return getFuncTable(funcName, args); }

    /** SQL SELECT libre. */
    public DBRowSet Query(String sql, Object... params)                    { return query(sql, params); }

    /** Primera fila de un SQL SELECT libre, o {@code null}. */
    public DBRow    QueryRow(String sql, Object... params)                 { return queryRow(sql, params); }

    /** Inserta una fila. Retorna el ID generado. */
    public long     Insert(String table, DBRow fields)                     { return insert(table, fields); }

    /** Actualiza filas que cumplan la condición. */
    public void     Update(String table, DBRow fields, String condition, Object... params) { update(table, fields, condition, params); }

    /** Elimina filas que cumplan la condición. */
    public void     Delete(String table, String condition, Object... params){ delete(table, condition, params); }

    /** Ejecuta DML crudo. Retorna filas afectadas. */
    public int      Exec(String sql, Object... params)                     { return exec(sql, params); }

    /** SELECT paginado. */
    public JxPageResult GetTablePaged(String table, String filter, int page, int size, Object... params) {
        return getTablePaged(table, filter, page, size, params);
    }

    /** SELECT con parámetros nombrados {@code :name}. */
    public DBRowSet QueryNamed(String sql, Map<String, Object> params) { return queryNamed(sql, params); }

    /** SELECT una fila con parámetros nombrados. */
    public DBRow QueryRowNamed(String sql, Map<String, Object> params) { return queryRowNamed(sql, params); }

    /** DML con parámetros nombrados. */
    public int ExecNamed(String sql, Map<String, Object> params) { return execNamed(sql, params); }

    /** INSERT en lote. */
    public int[] InsertBatch(String table, List<DBRow> rows) { return insertBatch(table, rows); }

    /** INSERT o UPDATE en lote. */
    public void UpsertBatch(String table, String pkField, List<DBRow> rows) { upsertBatch(table, pkField, rows); }
}
