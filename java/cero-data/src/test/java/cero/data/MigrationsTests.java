package cero.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

final class MigrationsTests {

    private MigrationsTests() {
    }

    static void run() throws Exception {
        Check.group("migraciones");

        DataSources.clear();
        DataSources.registerDefault(
                Pool.to("jdbc:h2:mem:migraciones;DB_CLOSE_DELAY=-1").validate(false).build());
        try {
            enOrdenYUnaVez();
            noSeReaplican();
            editarUnaAplicadaFalla();
            unaQueFallaNoDejaMediasTintas();
            desdeElClasspath();
            troceaRespetandoLiteralesYComentarios();
            repararReescribeLaHuella();
            verificarContraUnaBaseVacia();
            elFalloDiceQueMigracionYQueSentencia();
        } finally {
            DataSources.clear();
        }
    }

    private static void enOrdenYUnaVez() throws Exception {
        Path dir = Files.createTempDirectory("cero-mig");
        Files.writeString(dir.resolve("002_datos.sql"),
                "insert into clientes (nombre) values ('Ana');\n"
                        + "insert into clientes (nombre) values ('Beto');");
        Files.writeString(dir.resolve("001_esquema.sql"),
                "-- la tabla base\ncreate table clientes (id int auto_increment primary key,"
                        + " nombre varchar(80) not null);");

        int aplicadas = Migrations.from(dir).run();

        Check.equal("aplica las dos", aplicadas, 2);
        Check.equal("y en orden por nombre, no por fecha del archivo",
                Db.open().one("select count(*) as n from clientes").get("n").toString(), "2");
        Check.equal("las anota", Migrations.from(dir).applied(),
                List.of("001_esquema.sql", "002_datos.sql"));
    }

    private static void noSeReaplican() throws Exception {
        Path dir = Files.createTempDirectory("cero-mig2");
        Files.writeString(dir.resolve("001_tabla.sql"), "create table pedidos (id int primary key);");

        Check.equal("la primera vez se aplica", Migrations.from(dir).table("mig2").run(), 1);
        Check.equal("la segunda no hace nada", Migrations.from(dir).table("mig2").run(), 0);
    }

    /** Editar una migración ya corrida deja dos entornos creyéndose iguales. Tiene que doler. */
    private static void editarUnaAplicadaFalla() throws Exception {
        Path dir = Files.createTempDirectory("cero-mig3");
        Path archivo = dir.resolve("001_algo.sql");
        Files.writeString(archivo, "create table algo (id int primary key);");
        Migrations.from(dir).table("mig3").run();

        Files.writeString(archivo, "create table algo (id int primary key, extra varchar(10));");

        String mensaje = "";
        try {
            Migrations.from(dir).table("mig3").run();
        } catch (DataException esperado) {
            mensaje = esperado.getMessage();
        }
        Check.that("editar una ya aplicada falla", mensaje.contains("cambió desde entonces"));
        Check.that("y dice qué hacer", mensaje.contains("migración nueva"));
    }

    /**
     * Lo que sí se garantiza siempre: una migración que falla no queda anotada, así que se
     * reintenta. Deshacer sus efectos depende del motor — H2, como MySQL, confirma en cada DDL.
     */
    private static void unaQueFallaNoDejaMediasTintas() throws Exception {
        Path dir = Files.createTempDirectory("cero-mig4");
        Files.writeString(dir.resolve("001_rota.sql"),
                "create table buena (id int primary key);\nesto no es sql;");

        boolean protesto = false;
        try {
            Migrations.from(dir).table("mig4").run();
        } catch (RuntimeException esperado) {
            protesto = true;
        }
        Check.that("una migración rota falla", protesto);
        Check.equal("y no queda anotada, así que se reintentará",
                Migrations.from(dir).table("mig4").applied(), List.of());

        // En H2 el create table ya se confirmó por su cuenta: la tabla está aunque la migración
        // fallara. Es la limitación del motor, no del runner, y por eso se documenta en vez de
        // prometer lo contrario. La regla práctica es una migración, un cambio.
        Check.equal("en un motor sin DDL transaccional, lo hecho antes del fallo se queda",
                Db.open().one("select count(*) as n from information_schema.tables"
                        + " where table_name = 'BUENA'").get("n").toString(), "1");
    }

    private static void desdeElClasspath() {
        Check.equal("lee el índice y aplica lo que nombra",
                Migrations.fromClasspath("migraciones-prueba").table("mig5").run(), 1);
        Check.equal("y la anota",
                Migrations.fromClasspath("migraciones-prueba").table("mig5").applied(),
                List.of("001_inventario.sql"));
    }

    /** Un split(";") a secas parte por la mitad todo lo que lleve un punto y coma dentro. */
    private static void troceaRespetandoLiteralesYComentarios() {
        Check.equal("un literal con punto y coma no se parte",
                Migrations.sentencias("insert into t values ('a;b');"),
                List.of("insert into t values ('a;b')"));
        Check.equal("las comillas simples escapadas ('') no cierran el literal",
                Migrations.sentencias("insert into t values ('a''b;c');"),
                List.of("insert into t values ('a''b;c')"));
        Check.equal("un identificador entre comillas dobles tampoco",
                Migrations.sentencias("create table \"raro;nombre\" (id int);"),
                List.of("create table \"raro;nombre\" (id int)"));
        Check.equal("un comentario de línea se descarta con su punto y coma",
                Migrations.sentencias("select 1; -- ojo; aquí\nselect 2;"),
                List.of("select 1", "select 2"));
        Check.equal("un comentario de bloque también",
                Migrations.sentencias("/* ojo; aquí */ select 1;"), List.of("select 1"));
        Check.equal("y los de bloque anidan, como en PostgreSQL",
                Migrations.sentencias("/* fuera /* dentro; */ sigue; */ select 1;"),
                List.of("select 1"));
        Check.equal("un cuerpo $$ … $$ llega entero",
                Migrations.sentencias("create function f() returns int as $$ begin a; b; end; $$"
                        + " language plpgsql;\nselect 1;").size(), 2);
        Check.equal("y la etiqueta $cuerpo$ delimita igual",
                Migrations.sentencias("create function f() as $cuerpo$ x; y; $cuerpo$;").size(), 1);
        Check.equal("la última sentencia no necesita punto y coma",
                Migrations.sentencias("select 1"), List.of("select 1"));
    }

    /** Corregir una migración ya aplicada sin tocar el esquema no puede exigir SQL a mano. */
    private static void repararReescribeLaHuella() throws Exception {
        Path dir = Files.createTempDirectory("cero-mig6");
        Path archivo = dir.resolve("001_notas.sql");
        Files.writeString(archivo, "create table notas (id int primary key);");
        Migrations.from(dir).table("mig6").run();

        Files.writeString(archivo, "-- una aclaración\ncreate table notas (id int primary key);");

        Check.equal("repair dice qué huellas reescribió",
                Migrations.from(dir).table("mig6").repair(), List.of("001_notas.sql"));
        Check.equal("y después el arranque vuelve a pasar",
                Migrations.from(dir).table("mig6").run(), 0);
        Check.equal("sin nada que reparar no toca nada",
                Migrations.from(dir).table("mig6").repair(), List.of());
    }

    private static void verificarContraUnaBaseVacia() throws Exception {
        Path dir = Files.createTempDirectory("cero-mig7");
        Files.writeString(dir.resolve("001_ok.sql"), "create table ensayo (id int primary key);");
        DataSources.register("desechable",
                Pool.to("jdbc:h2:mem:ensayo;DB_CLOSE_DELAY=-1").validate(false).build());

        Migrations.Verificacion buena = Migrations.from(dir).table("mig7").verify("desechable");
        Check.that("el lote entero se aplica sobre una base vacía", buena.correcta());
        Check.equal("y dice cuántas", buena.aplicadas(), 1);
        Check.that("con un resumen legible", buena.resumen().contains("sin errores"));

        Files.writeString(dir.resolve("002_rota.sql"), "esto no es sql;");
        DataSources.register("desechable2",
                Pool.to("jdbc:h2:mem:ensayo2;DB_CLOSE_DELAY=-1").validate(false).build());
        Migrations.Verificacion mala = Migrations.from(dir).table("mig7").verify("desechable2");
        Check.that("una migración rota no lanza, se reporta", !mala.correcta());
        Check.equal("y se señala cuál", mala.migracion(), "002_rota.sql");
        Check.equal("con las que sí pasaron", mala.aplicadas(), 1);
    }

    /** El motor dice qué restricción se violó; solo el runner sabe en qué archivo iba. */
    private static void elFalloDiceQueMigracionYQueSentencia() {
        SQLException clave = new SQLException("ERROR: insert or update on table \"copias\" violates"
                + " foreign key constraint \"copias_medio_fk\"\n  Detail: Key (id)=(106) is not"
                + " present in table \"medios\".", "23503");
        DataException falla = Migrations.explicar("042_copias.sql", 3,
                new DataException("falló la consulta: insert ...", clave));

        Check.equal("dice el fichero, la sentencia y la fila que falta", falla.getMessage(),
                "la migración 042_copias.sql falló en la sentencia 3:"
                        + " la fila `medios.id=106` no existe");
        Check.equal("y encadena la causa original", falla.getCause().getCause(), clave);

        DataException otra = Migrations.explicar("007_x.sql", 1,
                new DataException("x", new SQLException("duplicate key value", "23505")));
        Check.that("sin detalle reconocible, al menos el SQLState y la primera línea",
                otra.getMessage().contains("[23505] duplicate key value"));
    }
}
