package lux.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

final class ValueTests {

    private ValueTests() {
    }

    record Articulo(long id, String titulo, int paginas) {
    }

    static void run() {
        Check.group("Row y Rows");
        filas();

        Check.group("Page");
        paginas();

        Check.group("parámetros con nombre");
        nombrados();
    }

    private static void filas() {
        Row row = Row.of("id", 7, "titulo", "Lux", "activo", true, "precio", "12.50");

        Check.equal("get devuelve el valor", row.get("titulo"), "Lux");
        Check.equal("text convierte", row.text("id"), "7");
        Check.equal("text de columna ausente es null", row.text("nada"), null);
        Check.equal("text con respaldo", row.text("nada", "—"), "—");
        Check.equal("integer", row.integer("id"), 7);
        Check.equal("asLong", row.asLong("id"), 7L);
        Check.equal("decimal desde texto", row.decimal("precio"), 12.5);
        Check.equal("exact devuelve BigDecimal", row.exact("precio"), new BigDecimal("12.50"));
        Check.equal("flag", row.flag("activo"), true);
        Check.equal("flag de columna ausente es false", row.flag("nada"), false);
        Check.equal("integer de columna ausente es cero", row.integer("nada"), 0);
        Check.that("has distingue", row.has("id") && !row.has("nada"));
        Check.equal("size", row.size(), 4);
        Check.that("columns conserva el orden",
                List.copyOf(row.columns()).equals(List.of("id", "titulo", "activo", "precio")));

        Check.equal("date desde texto", Row.of("f", "2026-08-01").date("f"), LocalDate.of(2026, 8, 1));
        Check.equal("moment desde texto", Row.of("f", "2026-08-01T10:30:00").moment("f"),
                LocalDateTime.of(2026, 8, 1, 10, 30));
        Check.equal("date de columna ausente es null", row.date("nada"), null);

        Check.equal("toJson", Row.of("a", 1, "b", "x").toJson(), "{\"a\":1,\"b\":\"x\"}");

        Row mayusculas = Row.of("ID", 7L, "TITULO", "Lux", "PAGINAS", 10);
        Check.equal("busca sin distinguir mayúsculas", mayusculas.text("titulo"), "Lux");
        Check.that("has tampoco distingue", mayusculas.has("id") && mayusculas.has("Id"));
        Check.equal("y vincula igual a un record",
                mayusculas.as(Articulo.class), new Articulo(7L, "Lux", 10));
        Check.that("conserva el nombre original en las columnas",
                mayusculas.columns().contains("TITULO"));
        Check.equal("as vincula a un record",
                Row.of("id", 1L, "titulo", "Lux", "paginas", 10).as(Articulo.class),
                new Articulo(1L, "Lux", 10));
        Check.equal("from construye desde un mapa", Row.from(Map.of("a", 1)).get("a"), 1);
        Check.raises("of con número impar de argumentos falla", IllegalArgumentException.class,
                () -> Row.of("a"));

        Rows rows = Rows.of(List.of(
                Row.of("id", 1L, "titulo", "uno", "paginas", 10),
                Row.of("id", 2L, "titulo", "dos", "paginas", 20)));

        Check.equal("size", rows.size(), 2);
        Check.equal("first", rows.first().text("titulo"), "uno");
        Check.equal("first de un conjunto vacío es null", Rows.empty().first(), null);
        Check.equal("column extrae una columna", rows.column("titulo"), List.of("uno", "dos"));
        Check.equal("as vincula todas las filas", rows.as(Articulo.class).size(), 2);
        Check.equal("as conserva los valores", rows.as(Articulo.class).get(1).titulo(), "dos");
        Check.that("toJson produce un array", rows.toJson().startsWith("[{") && rows.toJson().endsWith("}]"));
    }

    private static void paginas() {
        Page<String> page = new Page<>(List.of("a", "b"), 2, 2, 7);

        Check.equal("totalPages redondea hacia arriba", page.totalPages(), 4);
        Check.equal("offset", page.offset(), 2);
        Check.that("hasNext", page.hasNext());
        Check.that("hasPrevious", page.hasPrevious());
        Check.that("la primera página no tiene anterior",
                !new Page<>(List.of("a"), 1, 2, 7).hasPrevious());
        Check.that("la última página no tiene siguiente",
                !new Page<>(List.of("a"), 4, 2, 7).hasNext());
        Check.equal("total cero da cero páginas", new Page<>(List.of(), 1, 10, 0).totalPages(), 0);
        Check.equal("total exacto no añade página", new Page<>(List.of(), 1, 5, 10).totalPages(), 2);
        Check.equal("map transforma", page.map(String::toUpperCase).data(), List.of("A", "B"));
        Check.equal("map conserva el total", page.map(String::toUpperCase).total(), 7L);

        Check.raises("página cero se rechaza", IllegalArgumentException.class,
                () -> new Page<>(List.of(), 0, 10, 0));
        Check.raises("tamaño cero se rechaza", IllegalArgumentException.class,
                () -> new Page<>(List.of(), 1, 0, 0));
    }

    private static void nombrados() {
        Named simple = Named.compile("SELECT * FROM t WHERE id = :id", Map.of("id", 7));
        Check.equal("sustituye por marcador", simple.sql(), "SELECT * FROM t WHERE id = ?");
        Check.equal("y recoge el valor", simple.values()[0], 7);

        Named varios = Named.compile("WHERE a = :a AND b = :b OR a = :a",
                Map.of("a", 1, "b", 2));
        Check.equal("repetidos se expanden en orden", varios.sql(), "WHERE a = ? AND b = ? OR a = ?");
        Check.equal("con los valores repetidos", varios.values().length, 3);
        Check.equal("tercer valor", varios.values()[2], 1);

        Named enCadena = Named.compile("WHERE nota = ':literal' AND id = :id", Map.of("id", 3));
        Check.equal("no toca lo que hay dentro de comillas",
                enCadena.sql(), "WHERE nota = ':literal' AND id = ?");
        Check.equal("solo un valor", enCadena.values().length, 1);

        Check.raises("parámetro ausente falla", DataException.class,
                () -> Named.compile("WHERE id = :falta", Map.of()));
    }
}
