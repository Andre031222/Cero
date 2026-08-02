package jxmvc.controllers;

import jxmvc.core.*;
import jxmvc.core.JxMapping.*;
import jxmvc.models.TestModel;
import java.nio.file.Paths;

@JxControllerMapping("demo")
public class DemoController extends BaseController {

    private static final String DB_UNAVAILABLE = "Base de datos de demostración no configurada";

    // ── Demo básico ───────────────────────────────────────────────────────

    @JxGetMapping("index")
    public String index() {
        view.contentType("text/html;charset=UTF-8");
        return "Demo JxMVC 3.4.0 — <b>funcionando</b>";
    }

    @JxGetMapping("salud")
    public ActionResult saludo(String nome) {
        return text("Hola " + nome);
    }

    @JxGetMapping("ping")
    public ActionResult ping() {
        return json(GenApi.JsonStr("pong", true, "version", "3.4.0"));
    }

    // ── CORS ──────────────────────────────────────────────────────────────

    @JxCors(origins = {"http://localhost:5173","http://127.0.0.1:5173"}, hosts = {"127.0.0.1","::1"}, methods = {"GET"})
    @JxGetMapping("cors-local")
    public ActionResult corsLocal() {
        return json("{\"scope\":\"local\",\"ok\":true}");
    }

    @JxCors(origins = {"https://app.ejemplo.com"}, hosts = {"127.0.0.1","::1"}, methods = {"GET"})
    @JxGetMapping("cors-remote")
    public ActionResult corsRemote() {
        return json("{\"scope\":\"remote\",\"ok\":true}");
    }

    @JxCors(origins = {"*"}, methods = {"GET"})
    @JxGetMapping("cors-public")
    public ActionResult corsPublic() {
        return json("{\"scope\":\"public\",\"ok\":true}");
    }

    // ── Sesiones ──────────────────────────────────────────────────────────

    @JxGetMapping("crear")
    public ActionResult crear() {
        sessionSet("t1", "valor creado");
        return text("creado");
    }

    @JxGetMapping("ver")
    public ActionResult ver() {
        String val = (String) sessionGet("t1");
        return text(val != null ? "t1 = " + val : "nulo");
    }

    // ── Base de datos (solo perfil dev) ───────────────────────────────────

    @JxGetMapping("/inscrips")
    public void inscrips() {
        requireDev();
        TestModel db = new TestModel();
        if (!db.IsConnected()) { view.print(DB_UNAVAILABLE); return; }

        DBRowSet tbl = db.GetInscrips(null);
        model.setVar("connState", "Conectado");
        model.setVar("tbl",       tbl);
        model.setVar("hasRows",   !tbl.isEmpty());
    }

    @JxGetMapping("persona")
    public ActionResult persona() {
        requireDev();
        String id = model.arg(0);
        if (id == null || id.isBlank()) return json(GenApi.Error("Uso: /demo/persona/{id}"));

        TestModel db = new TestModel();
        if (!db.IsConnected()) return json(GenApi.Error(503, DB_UNAVAILABLE));

        DBRow per = db.FindBy("tblPersonas", "id", id);
        if (per == null) return json(GenApi.Error(404, "Persona no encontrada"));

        return json(GenApi.JsonStr(
            "success",  true,
            "inscrip",  per.Get("id"),
            "correo",   per.Get("Correo"),
            "datos",    per.Get("DatosPers"),
            "telefono", per.Get("Telefono"),
            "tipoprog", per.Get("tipProgra"),
            "programa", per.Get("idPrograma")
        ));
    }

    @JxGetMapping("dbremote")
    public ActionResult dbRemote() {
        requireDev();
        JxDB db = demoDb();
        if (db == null) return text(DB_UNAVAILABLE);
        try (db) {
            return db.isConnected() ? text("Conexión remota OK") : text(DB_UNAVAILABLE);
        }
    }

    // ── CRUD (solo dev; mutaciones por POST) ──────────────────────────────

    @JxGetMapping("test-list")
    public ActionResult testList() {
        requireDev();
        JxDB db = demoDb();
        if (db == null) return text(DB_UNAVAILABLE);
        try (db) {
            if (!db.isConnected()) return text(DB_UNAVAILABLE);
            DBRowSet rows = db.getTable("dicTest", "", "ORDER BY \"Id\"");
            StringBuilder sb = new StringBuilder("Filas: " + rows.size() + "\n");
            for (DBRow row : rows.result()) sb.append(renderRow(row)).append("\n");
            return text(sb.toString());
        }
    }

    @JxGetMapping("test-get")
    public ActionResult testGet() {
        requireDev();
        String raw = model.arg(0);
        if (raw == null || raw.isBlank()) return text("Uso: /demo/test-get/{id}");

        JxDB db = demoDb();
        if (db == null) return text(DB_UNAVAILABLE);
        try (db) {
            if (!db.isConnected()) return text(DB_UNAVAILABLE);
            DBRow row = db.queryRow("SELECT * FROM \"dicTest\" WHERE \"Id\" = ?",
                                    Integer.parseInt(raw));
            return row != null ? text(renderRow(row)) : text("No encontrado id=" + raw);
        } catch (NumberFormatException e) {
            throw JxException.badRequest("Id debe ser un número entero");
        }
    }

    @JxPostMapping("test-add")
    public ActionResult testAdd() {
        requireDev();
        String dni     = model.arg(0);
        String nombres = model.arg(1);
        if (dni == null || nombres == null) return text("Uso: POST /demo/test-add/{dni}/{nombres}");

        JxDB db = demoDb();
        if (db == null) return text(DB_UNAVAILABLE);
        try (db) {
            if (!db.isConnected()) return text(DB_UNAVAILABLE);
            long id = db.insert("dicTest", DBRow.of("DNI", dni, "Nombres", nombres));
            return text("Insertado Id=" + id);
        }
    }

    @JxPostMapping("test-update")
    public ActionResult testUpdate() {
        requireDev();
        String id      = model.arg(0);
        String nombres = model.arg(1);
        if (id == null || nombres == null) return text("Uso: POST /demo/test-update/{id}/{nombres}");

        JxDB db = demoDb();
        if (db == null) return text(DB_UNAVAILABLE);
        try (db) {
            if (!db.isConnected()) return text(DB_UNAVAILABLE);
            db.update("dicTest", DBRow.of("Nombres", nombres), "\"Id\" = ?", Integer.parseInt(id));
            return text("Actualizado Id=" + id);
        } catch (NumberFormatException e) {
            throw JxException.badRequest("Id debe ser número entero");
        }
    }

    @JxPostMapping("test-delete")
    public ActionResult testDelete() {
        requireDev();
        String id = model.arg(0);
        if (id == null || id.isBlank()) return text("Uso: POST /demo/test-delete/{id}");

        JxDB db = demoDb();
        if (db == null) return text(DB_UNAVAILABLE);
        try (db) {
            if (!db.isConnected()) return text(DB_UNAVAILABLE);
            db.delete("dicTest", "\"Id\" = ?", Integer.parseInt(id));
            return text("Eliminado Id=" + id);
        } catch (NumberFormatException e) {
            throw JxException.badRequest("Id debe ser número entero");
        }
    }

    // ── Subida de archivos ────────────────────────────────────────────────

    @JxGetMapping("upload")
    public ActionResult uploadForm() { requireDev(); return view("demo/upload"); }

    @JxPostMapping("upload")
    public ActionResult uploadPost() {
        requireDev();
        var saved = model.uploadFile("file", uploadDir(), "pdf,jpg,png");
        return saved != null ? text("OK: " + saved.getFileName()) : text("Error: " + model.lastError());
    }

    @JxGetMapping("testdb")
    public ActionResult testDb() { requireDev(); return view("demo/testdb"); }

    // ── Helpers ───────────────────────────────────────────────────────────

    private JxDB demoDb() {
        String url  = BaseDbResolver.property("jxmvc.demo.db.url",  "");
        String user = BaseDbResolver.property("jxmvc.demo.db.user", "");
        String pass = BaseDbResolver.property("jxmvc.demo.db.pass", "");
        if (url.isBlank()) return null;
        return new JxDB(url, user, pass);
    }

    private java.nio.file.Path uploadDir() {
        String base = BaseDbResolver.property("jxmvc.demo.upload.dir",
                System.getProperty("java.io.tmpdir"));
        return Paths.get(base, "jxmvc-uploads");
    }

    private String renderRow(DBRow row) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String k : row.keySet()) {
            if (!first) sb.append(" | ");
            sb.append(k).append("=").append(row.get(k));
            first = false;
        }
        return sb.toString();
    }
}
