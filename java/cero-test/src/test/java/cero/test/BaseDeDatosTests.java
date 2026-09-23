package cero.test;

import cero.data.Migrations;

import java.nio.file.Files;
import java.nio.file.Path;

final class BaseDeDatosTests {

    private BaseDeDatosTests() {
    }

    static void run() throws Exception {
        Check.group("migraciones contra una base desechable");

        Path buenas = migraciones(
                "001_usuarios.sql", "create table usuarios (id int primary key, nombre varchar(80))",
                "002_correo.sql", "alter table usuarios add column correo varchar(120)");

        try (TestDatabase db = TestDatabase.of("jdbc:h2:mem:cerotest_ok;DB_CLOSE_DELAY=-1")) {
            Check.equal("aplica el lote entero", db.migrate(Migrations.from(buenas)), 2);
            Check.equal("queda registrada como origen por omisión", db.source(), "default");
        }

        Path rotas = migraciones(
                "001_usuarios.sql", "create table usuarios (id int primary key)",
                "002_rota.sql", "esto no es sql");

        try (TestDatabase db = TestDatabase.of("jdbc:h2:mem:cerotest_ko;DB_CLOSE_DELAY=-1")) {
            Check.fails("una migración rota falla nombrando el archivo",
                    () -> db.migrate(Migrations.from(rotas)), "002_rota.sql");
        }
    }

    private static Path migraciones(String... nombreYSql) throws Exception {
        Path directorio = Files.createTempDirectory("cero-migraciones");
        directorio.toFile().deleteOnExit();
        for (int i = 0; i < nombreYSql.length; i += 2) {
            Path archivo = directorio.resolve(nombreYSql[i]);
            Files.writeString(archivo, nombreYSql[i + 1] + ";");
            archivo.toFile().deleteOnExit();
        }
        return directorio;
    }
}
