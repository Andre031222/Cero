/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  v3.1.1   : R. Andre Vilca Solorzano

package jxmvc.core;

/**
 * Generador de respuestas API — JSON plano sin dependencias externas.
 *
 * <p>Uso típico en controlador:
 * <pre>
 *   DBRow per = dbUser.GetRow("tblPersonas", "id = ?", id);
 *
 *   return GenApi.JsonStr(
 *       "success",  "true",
 *       "inscrip",  per.Get("id"),
 *       "correo",   per.Get("Correo"),
 *       "datos",    per.Get("DatosPers"),
 *       "telefono", per.Get("Telefono"),
 *       "tipoprog", per.Get("tipProgra"),
 *       "programa", per.Get("idPrograma")
 *   );
 * </pre>
 *
 * <p>Respuesta de error:
 * <pre>
 *   return GenApi.Error("Usuario no encontrado");
 *   return GenApi.Error(404, "No encontrado");
 * </pre>
 *
 * <p>Array desde {@link DBRowSet}:
 * <pre>
 *   DBRowSet rows = model.GetTable("tblProductos");
 *   return GenApi.JsonArray(rows);
 * </pre>
 */
public final class GenApi {

    private GenApi() {}

    /** Envuelve un fragmento JSON ya serializado para que no sea re-escapado. */
    static final class RawJson {
        final String json;
        RawJson(String json) { this.json = json; }
    }

    /**
     * Anida un objeto JSON dentro de otro {@code JsonStr} sin re-escapar.
     *
     * <pre>
     *   GenApi.JsonStr(
     *       "status", "UP",
     *       "pool",   GenApi.nested("enabled", true, "size", 10),
     *       "ws",     GenApi.nested("connections", 3)
     *   )
     *   // → {"status":"UP","pool":{"enabled":true,"size":10},"ws":{"connections":3}}
     * </pre>
     */
    public static RawJson nested(Object... keyValues) {
        return new RawJson(JsonStr(keyValues));
    }

    /**
     * Construye un JSON plano a partir de pares clave-valor.
     * Los valores nulos se serializan como {@code null}.
     * Los booleanos y números sin comillas; el resto con comillas.
     *
     * <pre>
     *   GenApi.JsonStr("ok", true, "id", 5, "nombre", "Ana")
     *   // → {"ok":true,"id":5,"nombre":"Ana"}
     * </pre>
     */
    public static String JsonStr(Object... keyValues) {
        if (keyValues.length % 2 != 0)
            throw new IllegalArgumentException("JsonStr requiere pares clave-valor");
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(',');
            String key = String.valueOf(keyValues[i]);
            Object val = keyValues[i + 1];
            sb.append('"').append(escapeJson(key)).append("\":").append(toJsonValue(val));
        }
        return sb.append('}').toString();
    }

    /**
     * Respuesta de éxito estándar: {@code {"success":true,"message":"..."}}
     */
    public static String Ok(String message) {
        return JsonStr("success", true, "message", message);
    }

    /**
     * Respuesta de error estándar: {@code {"success":false,"error":"..."}}
     */
    public static String Error(String message) {
        return JsonStr("success", false, "error", message);
    }

    /**
     * Respuesta de error con código HTTP: {@code {"success":false,"status":404,"error":"..."}}
     */
    public static String Error(int status, String message) {
        return JsonStr("success", false, "status", status, "error", message);
    }

    /**
     * Serializa un {@link DBRowSet} como array JSON.
     *
     * <pre>
     *   GenApi.JsonArray(model.GetTable("tblProductos"))
     *   // → [{"id":1,"nombre":"Prod A"},{"id":2,"nombre":"Prod B"}]
     * </pre>
     */
    public static String JsonArray(DBRowSet rs) {
        if (rs == null || rs.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DBRow row : rs.result()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(rowToJson(row));
        }
        return sb.append(']').toString();
    }

    /**
     * Serializa un {@link DBRow} como objeto JSON.
     *
     * <pre>
     *   GenApi.JsonRow(model.GetRow("tblPersonas", "id = ?", id))
     *   // → {"id":1,"nombre":"Ana","correo":"ana@mail.com"}
     * </pre>
     */
    public static String JsonRow(DBRow row) {
        if (row == null || row.isEmpty()) return "null";
        return rowToJson(row);
    }

    /**
     * Respuesta paginada estándar.
     *
     * <pre>
     *   GenApi.JsonPaged(page)
     *   // → {"total":150,"page":0,"size":20,"pages":8,"data":[...]}
     * </pre>
     */
    public static String JsonPaged(JxPageResult page) {
        return JsonStr(
            "total", page.getTotal(),
            "page",  page.getPage(),
            "size",  page.getSize(),
            "pages", page.getTotalPages(),
            "data",  page.getData()
        );
    }

    /**
     * Construye un array JSON de objetos a partir de múltiples {@code JsonStr} o {@code DBRow}.
     *
     * <pre>
     *   return Json(GenApi.JsonList(
     *       GenApi.nested("id", 1, "nombre", "Ana"),
     *       GenApi.nested("id", 2, "nombre", "Pedro")
     *   ));
     *   // → [{"id":1,"nombre":"Ana"},{"id":2,"nombre":"Pedro"}]
     * </pre>
     */
    public static String JsonList(Object... items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(toJsonValue(items[i]));
        }
        return sb.append(']').toString();
    }

    /**
     * Serializa cualquier objeto a JSON — String, Number, Boolean, DBRow, DBRowSet, RawJson, null.
     *
     * <pre>
     *   GenApi.toJson(per.Get("id"))     // → 5
     *   GenApi.toJson(per.GetString("nombre"))  // → "Ana"
     *   GenApi.toJson(rows)              // → [{"id":1,...},...]
     * </pre>
     */
    public static String toJson(Object val) {
        return toJsonValue(val);
    }

    // ── Internos ─────────────────────────────────────────────────────────

    private static String rowToJson(DBRow row) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : row.KeySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(key)).append("\":").append(toJsonValue(row.Get(key)));
        }
        return sb.append('}').toString();
    }

    static String toJsonValue(Object val) {
        if (val == null)              return "null";
        if (val instanceof RawJson r) return r.json;           // JSON crudo interno
        if (val instanceof Boolean)   return val.toString();
        if (val instanceof Number)    return val.toString();
        if (val instanceof DBRow r)   return rowToJson(r);
        if (val instanceof DBRowSet s)return JsonArray(s);
        if (val instanceof JxPageResult p) return JsonPaged(p);
        // String y todo lo demás: siempre con comillas y escape
        return '"' + escapeJson(val.toString()) + '"';
    }

    private static String escapeJson(String s) {
        return JxJson.escape(s);
    }
}
