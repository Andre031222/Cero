/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Repositorio CRUD genérico — equivalente ligero de Spring Data JPA.
 * Extiende esta clase para obtener operaciones CRUD sin escribir SQL.
 *
 * <p>Convenciones:
 * <ul>
 *   <li>El campo PK se llama {@code id} o está anotado con {@code @JxId}.</li>
 *   <li>El nombre de columna = nombre del campo Java (override con {@code @JxColumn}).</li>
 *   <li>La tabla se pasa al constructor o se deriva del nombre de la clase ({@code User} → {@code users}).</li>
 *   <li>El borrado lógico se activa con {@code @JxSoftDelete} en la entidad.</li>
 * </ul>
 *
 * <pre>
 *   // Entidad con borrado lógico
 *   &#64;JxSoftDelete(column = "deleted", deletedValue = "1")
 *   public class User {
 *       &#64;JxId public long id;
 *       public String name;
 *       public int deleted = 0;
 *   }
 *
 *   // Repositorio
 *   &#64;JxService
 *   public class UserRepository extends JxRepository&lt;User, Long&gt; {
 *       public UserRepository() { super("users", User.class); }
 *
 *       &#64;JxQuery("SELECT * FROM users WHERE email = ? AND deleted = 0")
 *       public List&lt;User&gt; findByEmail(String email) {
 *           return executeQuery(email);
 *       }
 *
 *       public List&lt;User&gt; findActive() {
 *           return findBy("status = ?", "active");
 *       }
 *   }
 *
 *   // En el controlador
 *   &#64;JxInject UserRepository users;
 *
 *   users.findAll();              // excluye borrados si @JxSoftDelete
 *   users.findById(1L);
 *   users.save(newUser);          // INSERT si id == 0, UPDATE si id > 0
 *   users.deleteById(id);         // UPDATE deleted=1 si @JxSoftDelete, DELETE si no
 *   users.hardDeleteById(id);     // DELETE siempre
 *   users.findAllPaged(0, 20);
 * </pre>
 *
 * @param <T>  Tipo de la entidad
 * @param <ID> Tipo del identificador primario (Long, Integer, String…)
 */
public abstract class JxRepository<T, ID> {

    private static final JxLogger log = JxLogger.getLogger(JxRepository.class);

    protected final String   tableName;
    protected final Class<T> entityType;

    private final Field  idField;
    private final String idColumn;

    // @JxSoftDelete metadata
    private final boolean softDelete;
    private final String  softDeleteColumn;
    private final String  softDeletedValue;
    private final String  softActiveValue;

    protected JxRepository(String tableName, Class<T> entityType) {
        this.tableName  = tableName;
        this.entityType = entityType;
        this.idField    = findIdField(entityType);
        this.idColumn   = idField != null ? columnName(idField) : "id";

        JxMapping.JxSoftDelete sd = entityType.getAnnotation(JxMapping.JxSoftDelete.class);
        this.softDelete       = sd != null;
        this.softDeleteColumn = sd != null ? sd.column()       : "deleted";
        this.softDeletedValue = sd != null ? sd.deletedValue() : "1";
        this.softActiveValue  = sd != null ? sd.activeValue()  : "0";
    }

    /**
     * Deriva el nombre de tabla del tipo.
     * Usa {@code @JxTable} si está presente, si no convierte CamelCase:
     * {@code UserOrder} → {@code user_orders}.
     */
    protected JxRepository(Class<T> entityType) {
        this(resolveTableName(entityType), entityType);
    }

    private static String resolveTableName(Class<?> cls) {
        JxMapping.JxTable t = cls.getAnnotation(JxMapping.JxTable.class);
        return (t != null && !t.value().isBlank()) ? t.value() : toTableName(cls.getSimpleName());
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    /** Retorna la entidad con ese ID o {@code null}. Excluye borrados lógicos. */
    public T findById(ID id) {
        try (JxDB db = new JxDB()) {
            String filter = idColumn + " = ?" + softActiveFilter(" AND ");
            DBRow row = db.getRow(tableName, filter, id);
            return row != null ? rowToEntity(row) : null;
        }
    }

    /** Retorna todas las filas. Excluye borrados lógicos automáticamente. */
    public List<T> findAll() {
        try (JxDB db = new JxDB()) {
            String cond = softCondition();
            if (cond.isBlank()) return toList(db.getTable(tableName));
            return toList(db.getTable(tableName, cond));
        }
    }

    /** Retorna filas que cumplan el filtro (soporta {@code ?} como placeholder). */
    public List<T> findBy(String filter, Object... params) {
        try (JxDB db = new JxDB()) {
            String full = filter + softActiveFilter(" AND ");
            return toList(db.getTable(tableName, full, params));
        }
    }

    /** Retorna la primera fila que cumpla el filtro, o {@code null}. */
    public T findOneBy(String filter, Object... params) {
        try (JxDB db = new JxDB()) {
            String full = filter + softActiveFilter(" AND ");
            DBRow row = db.getRow(tableName, full, params);
            return row != null ? rowToEntity(row) : null;
        }
    }

    /** Retorna un resultado paginado. */
    public JxPageResult findAllPaged(int page, int size) {
        try (JxDB db = new JxDB()) {
            String cond = softCondition();
            return db.getTablePaged(tableName, cond.isBlank() ? null : cond, page, size);
        }
    }

    /** Retorna un resultado paginado con filtro. */
    public JxPageResult findByPaged(String filter, int page, int size, Object... params) {
        try (JxDB db = new JxDB()) {
            String full = filter + softActiveFilter(" AND ");
            return db.getTablePaged(tableName, full, page, size, params);
        }
    }

    /**
     * Inserta o actualiza según el valor del campo PK.
     * Si el ID es {@code null} o {@code 0}, hace INSERT y devuelve el ID generado.
     * Si el ID ya tiene valor, hace UPDATE y devuelve el mismo ID.
     *
     * @return ID de la entidad tras la operación
     */
    @SuppressWarnings("unchecked")
    public ID save(T entity) {
        Object idVal = getIdValue(entity);
        boolean isNew = idVal == null
                || (idVal instanceof Number n && n.longValue() == 0)
                || (idVal instanceof String  s && s.isBlank());

        try (JxDB db = new JxDB()) {
            if (isNew) {
                DBRow row = entityToRow(entity, false);
                long generated = db.insert(tableName, row);
                setIdValue(entity, generated);
                Object idResult = (idField != null
                        && (idField.getType() == int.class || idField.getType() == Integer.class))
                        ? Integer.valueOf((int) generated)
                        : Long.valueOf(generated);
                return (ID) idResult;
            } else {
                DBRow row = entityToRow(entity, false);
                db.update(tableName, row, idColumn + " = ?", idVal);
                return (ID) idVal;
            }
        }
    }

    /**
     * Elimina la fila con el ID dado.
     * Si la entidad tiene {@code @JxSoftDelete}, hace UPDATE del campo de borrado.
     * Para borrado físico usar {@link #hardDeleteById(Object)}.
     */
    public void deleteById(ID id) {
        if (softDelete) {
            try (JxDB db = new JxDB()) {
                db.exec("UPDATE " + tableName + " SET " + softDeleteColumn
                        + " = ? WHERE " + idColumn + " = ?", softDeletedValue, id);
            }
        } else {
            try (JxDB db = new JxDB()) {
                db.delete(tableName, idColumn + " = ?", id);
            }
        }
    }

    /** Borrado físico — ignora {@code @JxSoftDelete}. Elimina la fila de la BD. */
    public void hardDeleteById(ID id) {
        try (JxDB db = new JxDB()) {
            db.delete(tableName, idColumn + " = ?", id);
        }
    }

    /** Elimina las filas que cumplan el filtro (sin borrado lógico). */
    public void deleteBy(String filter, Object... params) {
        try (JxDB db = new JxDB()) {
            db.delete(tableName, filter, params);
        }
    }

    /** Restaura un registro borrado lógicamente. */
    public void restore(ID id) {
        if (!softDelete) throw new JxException(400, "La entidad no tiene @JxSoftDelete");
        try (JxDB db = new JxDB()) {
            db.exec("UPDATE " + tableName + " SET " + softDeleteColumn
                    + " = ? WHERE " + idColumn + " = ?", softActiveValue, id);
        }
    }

    /** Total de filas activas (excluye borrados lógicos). */
    public long count() {
        try (JxDB db = new JxDB()) {
            String where = softActiveFilter("");
            String sql = "SELECT COUNT(*) AS _cnt FROM " + tableName
                    + (where.isBlank() ? "" : " " + where);
            DBRow row = db.queryRow(sql);
            if (row == null) return 0L;
            Object cnt = row.get("_cnt");
            return cnt instanceof Number n ? n.longValue() : 0L;
        }
    }

    /** {@code true} si existe una fila activa con ese ID. */
    public boolean existsById(ID id) {
        return findById(id) != null;
    }

    // ── @JxQuery support ─────────────────────────────────────────────────

    /**
     * Ejecuta el SQL definido en {@code @JxQuery} del método llamante.
     * Usar en métodos anotados con {@code @JxQuery}:
     *
     * <pre>
     *   &#64;JxQuery("SELECT * FROM users WHERE role = ? AND deleted = 0")
     *   public List&lt;User&gt; findByRole(String role) {
     *       return executeQuery(role);
     *   }
     * </pre>
     */
    protected List<T> executeQuery(Object... params) {
        String sql = resolveCallerQuery(2);
        try (JxDB db = new JxDB()) {
            return toList(db.query(sql, params));
        }
    }

    /**
     * Ejecuta el SQL de {@code @JxQuery} del método llamante y retorna la primera fila.
     *
     * <pre>
     *   &#64;JxQuery("SELECT * FROM users WHERE email = ? LIMIT 1")
     *   public User findByEmail(String email) {
     *       return executeQueryOne(email);
     *   }
     * </pre>
     */
    protected T executeQueryOne(Object... params) {
        String sql = resolveCallerQuery(2);
        try (JxDB db = new JxDB()) {
            DBRow row = db.queryRow(sql, params);
            return row != null ? rowToEntity(row) : null;
        }
    }

    /**
     * Ejecuta el SQL de {@code @JxQuery} del método llamante y retorna un escalar {@code long}.
     *
     * <pre>
     *   &#64;JxQuery("SELECT COUNT(*) AS _cnt FROM orders WHERE user_id = ?")
     *   public long countOrders(long userId) {
     *       return executeCount(userId);
     *   }
     * </pre>
     */
    protected long executeCount(Object... params) {
        String sql = resolveCallerQuery(2);
        try (JxDB db = new JxDB()) {
            DBRow row = db.queryRow(sql, params);
            if (row == null) return 0L;
            // Retornar el primer valor numérico encontrado
            for (String key : row.keySet()) {
                Object v = row.get(key);
                if (v instanceof Number n) return n.longValue();
            }
            return 0L;
        }
    }

    /** Resuelve el SQL de {@code @JxQuery} inspeccionando el stack de llamada. */
    private String resolveCallerQuery(int depth) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // depth+1 porque getStackTrace() agrega un frame extra
        if (stack.length <= depth + 1)
            throw new JxException(500, "@JxQuery: no se puede resolver el método llamante");

        String callerMethod = stack[depth + 1].getMethodName();
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(callerMethod)) {
                JxMapping.JxQuery q = m.getAnnotation(JxMapping.JxQuery.class);
                if (q != null) return q.value();
            }
        }
        throw new JxException(500, "El método '" + callerMethod + "' no tiene @JxQuery");
    }

    // ── Acceso raw a JxDB ─────────────────────────────────────────────────

    /**
     * Ejecuta una consulta SQL personalizada y retorna la lista de entidades.
     * Útil para JOINs y queries complejas.
     *
     * <pre>
     *   List&lt;User&gt; result = query(
     *       "SELECT u.* FROM users u JOIN roles r ON r.user_id = u.id WHERE r.name = ?",
     *       "admin");
     * </pre>
     */
    public List<T> query(String sql, Object... params) {
        try (JxDB db = new JxDB()) {
            return toList(db.query(sql, params));
        }
    }

    /** Ejecuta SQL directo (INSERT/UPDATE/DELETE). Retorna filas afectadas. */
    public int exec(String sql, Object... params) {
        try (JxDB db = new JxDB()) {
            return db.exec(sql, params);
        }
    }

    // ── Conversión Entidad ↔ DBRow ────────────────────────────────────────

    /**
     * Mapea un {@link DBRow} a la entidad directamente por reflexión de campos,
     * sin pasar por serialización/deserialización JSON.
     */
    private T rowToEntity(DBRow row) {
        try {
            T obj = entityType.getDeclaredConstructor().newInstance();
            for (Field f : collectFields(entityType)) {
                f.setAccessible(true);
                String col = columnName(f);
                Object val = row.get(col);
                if (val == null && !col.equals(f.getName())) val = row.get(f.getName());
                if (val != null) f.set(obj, coerceField(val, f.getType()));
            }
            return obj;
        } catch (Exception e) {
            log.warn("rowToEntity falló para {}: {}", entityType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static Object coerceField(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) return value;
        if (value instanceof Number n) {
            if (type == int.class     || type == Integer.class) return n.intValue();
            if (type == long.class    || type == Long.class)    return n.longValue();
            if (type == double.class  || type == Double.class)  return n.doubleValue();
            if (type == float.class   || type == Float.class)   return n.floatValue();
            if (type == short.class   || type == Short.class)   return n.shortValue();
            if (type == boolean.class || type == Boolean.class) return n.intValue() != 0;
        }
        if (type == String.class) return String.valueOf(value);
        if ((type == boolean.class || type == Boolean.class) && value instanceof Boolean b) return b;
        return value;
    }

    private List<T> toList(DBRowSet rs) {
        List<T> list = new ArrayList<>();
        for (DBRow row : rs.result()) {
            T entity = rowToEntity(row);
            if (entity != null) list.add(entity);
        }
        return list;
    }

    /**
     * Convierte la entidad a DBRow para INSERT/UPDATE.
     * @param includeId si {@code true} incluye el campo PK en el row.
     */
    private DBRow entityToRow(T entity, boolean includeId) {
        DBRow row = new DBRow();
        for (Field f : collectFields(entityType)) {
            boolean isId = (f == idField);
            if (isId && !includeId) continue;

            JxMapping.JxColumn col = f.getAnnotation(JxMapping.JxColumn.class);
            if (col != null && !col.insertable() && !includeId) continue;
            if (col != null && !col.updatable()  &&  includeId) continue;

            f.setAccessible(true);
            try {
                Object val = f.get(entity);
                if (val != null) row.add(columnName(f), val);
            } catch (IllegalAccessException e) {
                log.warn("entityToRow: no se pudo leer {}.{}: {}", entityType.getSimpleName(), f.getName(), e.getMessage());
            }
        }
        return row;
    }

    // ── Soft-delete helpers ───────────────────────────────────────────────

    /**
     * Condición SQL pura de borrado lógico (sin prefijo WHERE/AND).
     * Retorna {@code ""} si la entidad no tiene {@code @JxSoftDelete}.
     *
     * <pre>softCondition() → "deleted = 0"</pre>
     */
    private String softCondition() {
        return softDelete ? softDeleteColumn + " = " + softActiveValue : "";
    }

    /**
     * Añade la condición de borrado lógico a un filtro existente.
     *
     * @param prefix {@code " AND "} para añadir a un WHERE existente
     * @return p.ej. {@code " AND deleted = 0"}, o {@code ""} si no aplica
     */
    private String softActiveFilter(String prefix) {
        String cond = softCondition();
        return cond.isBlank() ? "" : prefix + cond;
    }

    // ── Helpers de reflexión ──────────────────────────────────────────────

    private Object getIdValue(T entity) {
        if (idField == null) return null;
        idField.setAccessible(true);
        try { return idField.get(entity); }
        catch (IllegalAccessException e) { return null; }
    }

    private void setIdValue(T entity, long generated) {
        if (idField == null) return;
        idField.setAccessible(true);
        try {
            if (idField.getType() == long.class    || idField.getType() == Long.class)    idField.set(entity, generated);
            else if (idField.getType() == int.class|| idField.getType() == Integer.class) idField.set(entity, (int) generated);
            else if (idField.getType() == String.class)                                   idField.set(entity, String.valueOf(generated));
        } catch (IllegalAccessException e) {
            log.warn("setIdValue falló en {}: {}", entityType.getSimpleName(), e.getMessage());
        }
    }

    private static Field findIdField(Class<?> cls) {
        for (Field f : collectFields(cls)) {
            if (f.getAnnotation(JxMapping.JxId.class) != null) return f;
        }
        for (Field f : collectFields(cls)) {
            if ("id".equalsIgnoreCase(f.getName())) return f;
        }
        return null;
    }

    private static String columnName(Field f) {
        JxMapping.JxColumn col = f.getAnnotation(JxMapping.JxColumn.class);
        return (col != null && !col.value().isBlank()) ? col.value() : f.getName();
    }

    private static List<Field> collectFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) fields.add(f);
            cls = cls.getSuperclass();
        }
        return fields;
    }

    /** {@code UserOrder} → {@code user_orders} */
    private static String toTableName(String className) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < className.length(); i++) {
            char c = className.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        String base = sb.toString();
        return base.endsWith("s") ? base : base + "s";
    }
}
