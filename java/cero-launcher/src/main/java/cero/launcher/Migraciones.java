package cero.launcher;

import cero.data.DataSources;
import cero.data.Migrations;
import cero.data.Pool;
import java.nio.file.Path;

/**
 * Detrás de {@code cero migraciones --verificar}: aplica el lote entero sobre una base vacía y
 * desechable y cuenta qué pasó, sin tocar la base de verdad.
 *
 * <pre>{@code
 * cero migraciones --verificar [directorio] [url-jdbc]
 * }</pre>
 */
public final class Migraciones {

    private static final String DESECHABLE = "desechable";
    private static final String POR_OMISION = "jdbc:h2:mem:cero_verificacion;DB_CLOSE_DELAY=-1";

    private Migraciones() {
    }

    public static void main(String[] args) {
        Path directorio = Path.of(args.length > 0 ? args[0] : "db/migraciones");
        String url = args.length > 1 ? args[1] : POR_OMISION;
        Migrations.Verificacion resultado;
        try {
            DataSources.register(DESECHABLE, Pool.to(url).validate(false).build());
            resultado = Migrations.from(directorio).verify(DESECHABLE);
        } catch (RuntimeException fallo) {
            System.err.println("no se pudo abrir la base desechable " + url + ": " + fallo.getMessage());
            System.err.println("el driver JDBC va en el classpath: CERO_JDBC_JAR=/ruta/driver.jar");
            System.exit(2);
            return;
        }
        System.out.println(resultado.resumen());
        if (!resultado.correcta()) {
            System.exit(1);
        }
    }
}
