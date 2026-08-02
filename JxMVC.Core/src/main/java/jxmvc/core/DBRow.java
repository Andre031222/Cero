/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  v3.1.1   : R. Andre Vilca Solorzano

package jxmvc.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fila de base de datos — mapa ordenado clave→valor.
 *
 * <p>Acceso directo a campos sin mapeo a POJOs:
 * <pre>
 *   DBRow per = model.GetRow("tblPersonas", "id = ?", id);
 *
 *   int    idVal  = per.GetInt("id");
 *   String correo = per.GetString("Correo");
 *   double monto  = per.GetDouble("monto");
 *   long   ts     = per.GetLong("timestamp");
 *   boolean activo = per.GetBool("activo");
 *
 *   // JSON directo desde el controlador
 *   return GenApi.JsonStr(
 *       "success", "true",
 *       "id",      per.Get("id"),
 *       "correo",  per.Get("Correo")
 *   );
 * </pre>
 */
public class DBRow {

    private final Map<String, Object> data = new LinkedHashMap<>();

    public DBRow() {}

    // ── API principal ────────────────────────────────────────────────────

    public void   Add(String key, Object val) { data.put(key, val); }
    public Object Get(String key)             { return data.get(key); }
    public Object Get(int i)                  { return data.values().stream().skip(i).findFirst().orElse(null); }
    public Set<String> KeySet()               { return data.keySet(); }
    public Map<String, Object> toMap()        { return Map.copyOf(data); }
    public boolean has(String key)            { return data.containsKey(key); }
    public boolean isEmpty()                  { return data.isEmpty(); }

    // ── Getters tipados ──────────────────────────────────────────────────

    /** Devuelve el valor como {@code String}, o {@code null} si no existe. */
    public String GetString(String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }

    /** Devuelve el valor como {@code int}, o {@code 0} si no existe o no es numérico. */
    public int GetInt(String key) {
        Object v = data.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Devuelve el valor como {@code long}, o {@code 0} si no existe o no es numérico. */
    public long GetLong(String key) {
        Object v = data.get(key);
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); } catch (NumberFormatException e) { return 0L; }
    }

    /** Devuelve el valor como {@code double}, o {@code 0.0} si no existe o no es numérico. */
    public double GetDouble(String key) {
        Object v = data.get(key);
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString().trim()); } catch (NumberFormatException e) { return 0.0; }
    }

    /** Devuelve el valor como {@code boolean}. Acepta 1/0, "true"/"false", "1"/"0". */
    public boolean GetBool(String key) {
        Object v = data.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = v.toString().trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    /** Devuelve el valor como {@code float}, o {@code 0f} si no existe. */
    public float GetFloat(String key) {
        Object v = data.get(key);
        if (v == null) return 0f;
        if (v instanceof Number n) return n.floatValue();
        try { return Float.parseFloat(v.toString().trim()); } catch (NumberFormatException e) { return 0f; }
    }

    /**
     * Devuelve el valor como {@link BigDecimal}, o {@code BigDecimal.ZERO} si no existe.
     * Ideal para campos monetarios donde la precisión decimal importa.
     */
    public BigDecimal GetBigDecimal(String key) {
        Object v = data.get(key);
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString().trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /**
     * Devuelve el valor como {@link LocalDate}, o {@code null} si no existe o no es fecha.
     * Acepta {@code java.sql.Date}, {@code LocalDate} y {@code String} en formato ISO.
     */
    public LocalDate GetDate(String key) {
        Object v = data.get(key);
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        try { return LocalDate.parse(v.toString().trim()); } catch (Exception e) { return null; }
    }

    /**
     * Devuelve el valor como {@link LocalDateTime}, o {@code null} si no existe.
     */
    public LocalDateTime GetDateTime(String key) {
        Object v = data.get(key);
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        try { return LocalDateTime.parse(v.toString().trim()); } catch (Exception e) { return null; }
    }

    /**
     * Serializa esta fila como JSON usando {@link GenApi}.
     *
     * <pre>
     *   DBRow row = db.GetRow("tblPersonas", "id = ?", 1);
     *   String json = row.toJson();
     *   // → {"id":1,"nombre":"Ana","correo":"ana@mail.com"}
     * </pre>
     */
    public String toJson() {
        return GenApi.JsonRow(this);
    }

    /**
     * Fábrica: {@code DBRow.of("campo1", valor1, "campo2", valor2, ...)}
     */
    public static DBRow of(Object... keyValues) {
        if (keyValues.length % 2 != 0)
            throw new IllegalArgumentException("Se requiere número par de argumentos (clave, valor, ...)");
        DBRow row = new DBRow();
        for (int i = 0; i < keyValues.length; i += 2)
            row.Add((String) keyValues[i], keyValues[i + 1]);
        return row;
    }

    // ── Alias camelCase (internos del framework) ─────────────────────────
    public void   add(String k, Object v) { Add(k, v); }
    public Object get(String k)           { return Get(k); }
    public String getString(String k)     { return GetString(k); }
    public Set<String> keySet()           { return KeySet(); }
}
