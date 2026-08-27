package corvo.data;

import java.util.List;
import java.util.Map;

final class DbTests {

    private DbTests() {
    }

    static void run() {
        Check.group("Db: SQL generado y parámetros");
        sql();

        Check.group("Db: mapeo de resultados");
        mapeo();

        Check.group("Db: paginación e identificadores");
        paginacionYSeguridad();
    }

    private static void prepare() {
        DataSources.clear();
        FakeDb.reset();
        DataSources.registerDefault(Pool.to(FakeDb.URL).validate(false).build());
    }

    private static void sql() {
        prepare();
        Db db = Db.open();

        db.select("usuarios");
        Check.equal("select sin filtro", FakeDb.lastQuery().sql(), "SELECT * FROM usuarios");

        db.select("usuarios", "edad > ?", 18);
        Check.equal("select con filtro", FakeDb.lastQuery().sql(),
                "SELECT * FROM usuarios WHERE edad > ?");
        Check.equal("y su parámetro", FakeDb.lastQuery().params(), List.of(18));

        db.insert("usuarios", Row.of("nombre", "Ana", "edad", 30));
        Check.equal("insert nombra las columnas", FakeDb.lastQuery().sql(),
                "INSERT INTO usuarios (nombre, edad) VALUES (?, ?)");
        Check.equal("insert liga los valores en orden",
                FakeDb.lastQuery().params(), List.of("Ana", 30));

        db.update("usuarios", Row.of("nombre", "Luis"), "id = ?", 7);
        Check.equal("update genera SET y WHERE", FakeDb.lastQuery().sql(),
                "UPDATE usuarios SET nombre = ? WHERE id = ?");
        Check.equal("update liga primero SET y luego WHERE",
                FakeDb.lastQuery().params(), List.of("Luis", 7));

        db.delete("usuarios", "id = ?", 3);
        Check.equal("delete", FakeDb.lastQuery().sql(), "DELETE FROM usuarios WHERE id = ?");

        db.exec("UPDATE t SET a = ?", 1);
        Check.equal("exec pasa el SQL tal cual", FakeDb.lastQuery().sql(), "UPDATE t SET a = ?");

        db.queryNamed("SELECT * FROM t WHERE id = :id", Map.of("id", 9));
        Check.equal("queryNamed traduce a marcadores", FakeDb.lastQuery().sql(),
                "SELECT * FROM t WHERE id = ?");
        Check.equal("y liga el valor", FakeDb.lastQuery().params(), List.of(9));

        db.insertBatch("usuarios", List.of(
                Row.of("nombre", "Ana"), Row.of("nombre", "Luis")));
        Check.equal("insertBatch usa una sola sentencia", FakeDb.lastQuery().sql(),
                "INSERT INTO usuarios (nombre) VALUES (?)");
        Check.equal("insertBatch registra cada fila", FakeDb.lastQuery().params(), List.of("Luis"));

        Check.raises("insert sin columnas falla", IllegalArgumentException.class,
                () -> db.insert("usuarios", new Row()));
        Check.raises("update sin columnas falla", IllegalArgumentException.class,
                () -> db.update("usuarios", new Row(), "id = ?", 1));
    }

    private static void mapeo() {
        prepare();
        Db db = Db.open();

        FakeDb.willReturn(List.of(
                FakeDb.row("id", 1L, "nombre", "Ana"),
                FakeDb.row("id", 2L, "nombre", "Luis")));
        Rows rows = db.select("usuarios");
        Check.equal("lee todas las filas", rows.size(), 2);
        Check.equal("y sus columnas", rows.get(1).text("nombre"), "Luis");

        FakeDb.willReturn(List.of(FakeDb.row("id", 5L, "nombre", "Ana")));
        Check.equal("one devuelve la primera", db.one("SELECT 1").asLong("id"), 5L);

        FakeDb.willReturn(List.of());
        Check.equal("one sin resultados devuelve null", db.one("SELECT 1"), null);

        FakeDb.willReturn(List.of(FakeDb.row("id", 1L, "titulo", "Corvo", "paginas", 10)));
        Check.equal("query tipado vincula a record",
                db.query(ValueTests.Articulo.class, "SELECT 1").get(0),
                new ValueTests.Articulo(1L, "Corvo", 10));

        FakeDb.willReturn(List.of());
        Check.equal("one tipado sin resultados es null",
                db.one(ValueTests.Articulo.class, "SELECT 1"), null);

        FakeDb.willReturn(List.of(FakeDb.row("total", 42L)));
        Check.equal("count lee el agregado", db.count("usuarios", null), 42L);
        Check.equal("count genera COUNT(*)", FakeDb.lastQuery().sql(),
                "SELECT COUNT(*) AS total FROM usuarios");

        FakeDb.willUpdate(3);
        Check.equal("exec devuelve las filas afectadas", db.exec("DELETE FROM t"), 3);

        long clave = db.insert("usuarios", Row.of("nombre", "Ana"));
        Check.that("insert devuelve la clave generada", clave > 0);
    }

    private static void paginacionYSeguridad() {
        prepare();
        Db db = Db.open();

        FakeDb.willReturn(List.of(FakeDb.row("total", 25L)));
        FakeDb.willReturn(List.of(FakeDb.row("id", 11L), FakeDb.row("id", 12L)));

        Page<Row> page = db.page("usuarios", "activo = ?", 2, 10, true);

        Check.equal("primero cuenta", FakeDb.queryAt(0).sql(),
                "SELECT COUNT(*) AS total FROM usuarios WHERE activo = ?");
        Check.equal("luego pide la página", FakeDb.queryAt(1).sql(),
                "SELECT * FROM usuarios WHERE activo = ? LIMIT ? OFFSET ?");
        Check.equal("con filtro, límite y desplazamiento",
                FakeDb.queryAt(1).params(), List.of(true, 10, 10));
        Check.equal("total leído del conteo", page.total(), 25L);
        Check.equal("páginas totales", page.totalPages(), 3);
        Check.equal("datos de la página", page.data().size(), 2);

        Check.raises("página cero se rechaza", IllegalArgumentException.class,
                () -> db.page("usuarios", null, 0, 10));

        Check.raises("nombre de tabla con espacio se rechaza", IllegalArgumentException.class,
                () -> db.select("usuarios; DROP TABLE x"));
        Check.raises("nombre de tabla con comilla se rechaza", IllegalArgumentException.class,
                () -> db.select("usuarios'"));
        Check.raises("nombre de columna con paréntesis se rechaza", IllegalArgumentException.class,
                () -> db.insert("t", Row.of("a)", 1)));
        Check.that("un nombre con esquema sí se acepta",
                Db.identifier("public.usuarios").equals("public.usuarios"));
    }
}
