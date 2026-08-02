package jxmvc.models;

import jxmvc.core.DBRow;
import jxmvc.core.DBRowSet;
import jxmvc.core.JxDB;

//
// Patrón JxMVC v3: el Modelo extiende JxDB directamente.
// Los campos se leen con DBRow.GetInt / GetString / Get sin mapeo a POJOs.
// Las consultas usan Views y cruces definidos en la BD, no entidades mapeadas.
// Conexión por defecto desde application.properties.
//
public class TestModel extends JxDB {

    public TestModel() { super(); }

    // ── Consultas de tabla/vista ──────────────────────────────────────────

    /** Todas las inscripciones, con filtro WHERE opcional. */
    public DBRowSet GetInscrips(String filter, Object... params) {
        if (filter == null || filter.isBlank()) return GetTable("tblInscrips");
        return GetTable("tblInscrips", filter, params);
    }

    /** Lista de un diccionario / catálogo con filtro. */
    public DBRowSet DicOficinas(String filter) {
        return GetTable("dicOficinas", filter);
    }

    // ── Búsquedas por campo ───────────────────────────────────────────────

    /** Primera fila de cualquier tabla donde field = id (parámetro seguro). */
    public DBRow FindBy(String table, String field, String id) {
        return GetRow(table, field + " = ?", id);
    }

    /** Todas las filas de cualquier tabla donde field = id, ordenadas por id. */
    public DBRowSet FindAllById(String table, String field, String id) {
        return GetTable(table, field + " = ?", "ORDER BY id", id);
    }

    // ── Ejemplo de uso en controlador ────────────────────────────────────
    //
    //   TestModel db = new TestModel();
    //   DBRow per = db.FindBy("tblPersonas", "id", idParam);
    //
    //   return GenApi.JsonStr(
    //       "success",  "true",
    //       "inscrip",  per.Get("id"),
    //       "correo",   per.Get("Correo"),
    //       "datos",    per.Get("DatosPers"),
    //       "telefono", per.Get("Telefono"),
    //       "tipoprog", per.Get("tipProgra"),
    //       "programa", per.Get("idPrograma")
    //   );
}
