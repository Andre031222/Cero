/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Conjunto de filas devuelto por una consulta.
 *
 * <pre>
 *   DBRowSet rows = db.getTable("users");
 *   for (DBRow row : rows.result()) { ... }
 *   DBRow first = rows.first();
 *   boolean empty = rows.isEmpty();
 * </pre>
 */
public class DBRowSet {

    private final List<DBRow> rows = new ArrayList<>();

    public DBRowSet() {}

    public void add(DBRow row)       { rows.add(row); }
    public DBRow get(int index)      { return rows.get(index); }
    public DBRow first()             { return rows.isEmpty() ? null : rows.get(0); }
    public int size()                { return rows.size(); }
    public boolean isEmpty()         { return rows.isEmpty(); }
    public Iterable<DBRow> result()  { return rows; }
    public List<DBRow> asList()      { return List.copyOf(rows); }

    // ── API PascalCase — primera clase ───────────────────────────────────
    public void            Add(DBRow r)   { add(r); }
    public DBRow           Get(int i)     { return get(i); }
    public DBRow           First()        { return first(); }
    public int             Size()         { return size(); }
    public boolean         IsEmpty()      { return isEmpty(); }
    public Iterable<DBRow> Result()       { return result(); }
}
