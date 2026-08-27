package corvo.data;

import java.nio.file.Files;
import java.nio.file.Path;
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
        } finally {
            DataSources.clear();
        }
    }

    private static void enOrdenYUnaVez() throws Exception {
        Path dir = Files.createTempDirectory("lux-mig");
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
        Path dir = Files.createTempDirectory("lux-mig2");
        Files.writeString(dir.resolve("001_tabla.sql"), "create table pedidos (id int primary key);");

        Check.equal("la primera vez se aplica", Migrations.from(dir).table("mig2").run(), 1);
        Check.equal("la segunda no hace nada", Migrations.from(dir).table("mig2").run(), 0);
    }

    /** Editar una migración ya corrida deja dos entornos creyéndose iguales. Tiene que doler. */
    private static void editarUnaAplicadaFalla() throws Exception {
        Path dir = Files.createTempDirectory("lux-mig3");
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
        Path dir = Files.createTempDirectory("lux-mig4");
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
}
