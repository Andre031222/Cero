/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Resultado paginado de una consulta.
 * Retornado por {@link JxDB#getTablePaged} y serializable a JSON con {@link JxJson}.
 *
 * <pre>
 *   // Controller
 *   &#64;JxGetMapping("list")
 *   public ActionResult list(&#64;JxParam("page") int page,
 *                            &#64;JxParam("size") int size) {
 *       JxPageResult result;
 *       try (JxDB db = new JxDB()) {
 *           result = db.getTablePaged("users", "status = ?", page, size, "active");
 *       }
 *       return json(result);
 *   }
 *
 *   // JSON de respuesta:
 *   {
 *     "page": 0, "size": 20, "total": 150, "totalPages": 8,
 *     "hasNext": true, "hasPrev": false,
 *     "data": [ {...}, {...} ]
 *   }
 * </pre>
 */
public final class JxPageResult {

    private final DBRowSet data;
    private final int      page;
    private final int      size;
    private final long     total;
    private final int      totalPages;

    private JxPageResult(DBRowSet data, int page, int size, long total, int totalPages) {
        this.data       = data;
        this.page       = page;
        this.size       = size;
        this.total      = total;
        this.totalPages = totalPages;
    }

    // ── Factory ───────────────────────────────────────────────────────────

    public static JxPageResult of(DBRowSet data, int page, int size, long total) {
        int pages = size > 0 ? (int) Math.ceil((double) total / size) : 1;
        return new JxPageResult(data, page, Math.max(1, size), total, Math.max(0, pages));
    }

    // ── Getters (usados por JxJson.reflectToJson) ─────────────────────────

    public DBRowSet getData()       { return data; }
    public int      getPage()       { return page; }
    public int      getSize()       { return size; }
    public long     getTotal()      { return total; }
    public int      getTotalPages() { return totalPages; }
    public boolean  isHasNext()     { return page < totalPages - 1; }
    public boolean  isHasPrev()     { return page > 0; }
    public int      getOffset()     { return page * size; }
}
