package cero.data;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class MotorTests {

    private MotorTests() {
    }

    @Table("articulos")
    record Articulo(
            @Id long id,
            String titulo,
            @Column("precio_venta") BigDecimal precio,
            @Column("publicado") boolean activo,
            LocalDate creado) {
    }

    private record Motor(String nombre, String url, String usuario, String clave, String ddl) {
    }

    private static final Motor[] MOTORES = {
            new Motor("H2", "jdbc:h2:mem:motores;DB_CLOSE_DELAY=-1", null, null, """
                    CREATE TABLE articulos (
                        id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                        titulo        VARCHAR(120)   NOT NULL,
                        precio_venta  DECIMAL(10, 2) NOT NULL,
                        publicado     BOOLEAN        NOT NULL,
                        creado        DATE           NOT NULL
                    )"""),

            new Motor("PostgreSQL", "jdbc:postgresql://127.0.0.1:55432/ceropruebas", "postgres", "cero", """
                    CREATE TABLE articulos (
                        id            BIGSERIAL      PRIMARY KEY,
                        titulo        VARCHAR(120)   NOT NULL,
                        precio_venta  DECIMAL(10, 2) NOT NULL,
                        publicado     BOOLEAN        NOT NULL,
                        creado        DATE           NOT NULL
                    )"""),

            new Motor("MySQL", "jdbc:mysql://127.0.0.1:53306/ceropruebas", "root", "cero", """
                    CREATE TABLE articulos (
                        id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                        titulo        VARCHAR(120)   NOT NULL,
                        precio_venta  DECIMAL(10, 2) NOT NULL,
                        publicado     BOOLEAN        NOT NULL,
                        creado        DATE           NOT NULL
                    )"""),
    };

    static void run() {
        for (Motor motor : MOTORES) {
            if (!alcanzable(motor)) {
                Check.group("motor real · " + motor.nombre());
                Check.omitido(motor.nombre() + " no está accesible en " + motor.url());
                continue;
            }
            Check.group("motor real · " + motor.nombre());
            try {
                probar(motor);
            } catch (RuntimeException fallo) {
                Check.that("la batería completa contra " + motor.nombre()
                        + " (" + fallo.getClass().getSimpleName() + ": " + fallo.getMessage() + ")", false);
            } finally {
                DataSources.clear();
            }
        }
    }

    private static void probar(Motor motor) {
        DataSources.clear();
        DataSources.registerDefault(Pool.to(motor.url())
                .credentials(motor.usuario(), motor.clave())
                .maxSize(4)
                .build());

        Db db = Db.open();
        db.exec("DROP TABLE IF EXISTS articulos");
        db.exec(motor.ddl());

        escrituraYClaves(db);
        lectura(db);
        tipos(db);
        paginacion(db);
        mayusculas(db);
        transacciones(db);
        repositorio();
        lote(db);
    }

    private static void escrituraYClaves(Db db) {
        long id = db.insert("articulos", Row.of(
                "titulo", "Primero",
                "precio_venta", new BigDecimal("19.90"),
                "publicado", true,
                "creado", LocalDate.of(2026, 8, 1)));
        Check.that("insert devuelve una clave generada (" + id + ")", id > 0);

        long segundo = db.insert("articulos", Row.of(
                "titulo", "Segundo",
                "precio_venta", new BigDecimal("5.00"),
                "publicado", false,
                "creado", LocalDate.of(2026, 8, 2)));
        Check.that("la segunda clave es distinta y mayor", segundo > id);

        Check.equal("update afecta una fila",
                db.update("articulos", Row.of("titulo", "Primero corregido"), "id = ?", id), 1);
        Check.equal("el cambio se lee",
                db.selectOne("articulos", "id = ?", id).text("titulo"), "Primero corregido");
    }

    private static void lectura(Db db) {
        Check.equal("select devuelve las dos filas", db.select("articulos").size(), 2);
        Check.equal("select con filtro", db.select("articulos", "publicado = ?", true).size(), 1);
        Check.equal("count sin filtro", db.count("articulos", null), 2L);
        Check.equal("count con filtro", db.count("articulos", "publicado = ?", false), 1L);
        Check.equal("selectOne inexistente devuelve null",
                db.selectOne("articulos", "id = ?", 9999), null);

        Check.equal("parámetros con nombre",
                db.queryNamed("SELECT titulo FROM articulos WHERE publicado = :p",
                        Map.of("p", true)).size(), 1);
    }

    private static void tipos(Db db) {
        Row fila = db.selectOne("articulos", "titulo = ?", "Segundo");

        Check.equal("texto", fila.text("titulo"), "Segundo");
        Check.that("clave numérica", fila.asLong("id") > 0);
        Check.equal("decimal exacto", fila.exact("precio_venta").compareTo(new BigDecimal("5.00")), 0);
        Check.equal("decimal como double", fila.decimal("precio_venta"), 5.0);
        Check.equal("booleano falso", fila.flag("publicado"), false);
        Check.equal("booleano verdadero",
                db.selectOne("articulos", "titulo = ?", "Primero corregido").flag("publicado"), true);
        Check.equal("fecha", fila.date("creado"), LocalDate.of(2026, 8, 2));

        Articulo mapeado = fila.as(Articulo.class);
        Check.equal("mapea a record: título", mapeado.titulo(), "Segundo");
        Check.equal("mapea a record: @Column", mapeado.precio().compareTo(new BigDecimal("5.00")), 0);
        Check.equal("mapea a record: booleano", mapeado.activo(), false);
        Check.equal("mapea a record: fecha", mapeado.creado(), LocalDate.of(2026, 8, 2));
    }

    private static void paginacion(Db db) {
        for (int i = 3; i <= 12; i++) {
            db.insert("articulos", Row.of(
                    "titulo", "Artículo " + i,
                    "precio_venta", new BigDecimal(i + ".50"),
                    "publicado", i % 2 == 0,
                    "creado", LocalDate.of(2026, 8, 1)));
        }

        Page<Row> primera = db.page("articulos", null, 1, 5);
        Check.equal("página 1 trae 5", primera.data().size(), 5);
        Check.equal("total correcto", primera.total(), 12L);
        Check.equal("páginas totales", primera.totalPages(), 3);

        Page<Row> tercera = db.page("articulos", null, 3, 5);
        Check.equal("página 3 trae las 2 restantes", tercera.data().size(), 2);
        Check.that("y no tiene siguiente", !tercera.hasNext());

        Page<Row> filtrada = db.page("articulos", "publicado = ?", 1, 4, true);
        Check.that("paginación con filtro respeta el total", filtrada.total() > 0);
        Check.that("y no devuelve más del tamaño", filtrada.data().size() <= 4);
    }

    private static void mayusculas(Db db) {
        Row fila = db.query("SELECT id, titulo AS TITULO_MAYUS FROM articulos WHERE titulo = ?",
                "Segundo").first();
        Check.that("un alias en mayúsculas se encuentra en minúsculas",
                fila.text("titulo_mayus") != null);
        Check.that("y también tal cual viene", fila.text("TITULO_MAYUS") != null);

        Row conteo = db.query("SELECT COUNT(*) AS total FROM articulos").first();
        Check.that("COUNT(*) se lee sin importar cómo lo devuelva el motor",
                conteo.asLong("total") == 12L);
    }

    private static void transacciones(Db db) {
        long antes = db.count("articulos", null);

        Tx.run(() -> Db.open().insert("articulos", Row.of(
                "titulo", "Dentro de transacción",
                "precio_venta", new BigDecimal("1.00"),
                "publicado", true,
                "creado", LocalDate.of(2026, 8, 3))));
        Check.equal("la transacción confirmada persiste", db.count("articulos", null), antes + 1);

        try {
            Tx.run(() -> {
                Db.open().insert("articulos", Row.of(
                        "titulo", "Se deshace",
                        "precio_venta", new BigDecimal("2.00"),
                        "publicado", true,
                        "creado", LocalDate.of(2026, 8, 3)));
                throw new IllegalStateException("fallo provocado");
            });
        } catch (IllegalStateException esperado) {
            Check.that("la excepción se propaga", true);
        }
        Check.equal("y la transacción se deshizo", db.count("articulos", null), antes + 1);
        Check.equal("la fila descartada no está",
                db.selectOne("articulos", "titulo = ?", "Se deshace"), null);
    }

    private static void repositorio() {
        Repository<Articulo, Long> repo = new Repository<>(Articulo.class);

        long id = repo.insert(new Articulo(0, "Desde el repositorio",
                new BigDecimal("42.00"), true, LocalDate.of(2026, 8, 4)));
        Check.that("el repositorio inserta y devuelve la clave", id > 0);

        Articulo leido = repo.findById(id);
        Check.equal("findById recupera el título", leido.titulo(), "Desde el repositorio");
        Check.equal("y el decimal", leido.precio().compareTo(new BigDecimal("42.00")), 0);

        Check.equal("update por id",
                repo.update(new Articulo(id, "Actualizado", new BigDecimal("43.00"),
                        false, LocalDate.of(2026, 8, 4))), 1);
        Check.equal("el cambio se lee", repo.findById(id).titulo(), "Actualizado");

        Check.that("existsById", repo.existsById(id));
        Check.that("count crece", repo.count() > 0);
        Check.equal("findPage devuelve entidades",
                repo.findPage(1, 3).data().size(), 3);
        Check.equal("deleteById", repo.deleteById(id), 1);
        Check.that("y ya no existe", !repo.existsById(id));
    }

    private static void lote(Db db) {
        long antes = db.count("articulos", null);
        int[] resultado = db.insertBatch("articulos", List.of(
                Row.of("titulo", "Lote 1", "precio_venta", new BigDecimal("1.00"),
                        "publicado", true, "creado", LocalDate.of(2026, 8, 5)),
                Row.of("titulo", "Lote 2", "precio_venta", new BigDecimal("2.00"),
                        "publicado", false, "creado", LocalDate.of(2026, 8, 5))));

        Check.equal("insertBatch informa dos filas", resultado.length, 2);
        Check.equal("y las dos están", db.count("articulos", null), antes + 2);
    }

    private static boolean alcanzable(Motor motor) {
        try (var conexion = motor.usuario() == null
                ? DriverManager.getConnection(motor.url())
                : DriverManager.getConnection(motor.url(), motor.usuario(), motor.clave())) {
            return conexion.isValid(2);
        } catch (Exception inalcanzable) {
            return false;
        }
    }
}
