package cero.test;

import cero.data.DataSources;
import cero.data.Migrations;
import cero.data.Pool;

/**
 * Una base desechable con las migraciones del proyecto ya aplicadas.
 *
 * <pre>{@code
 * try (TestDatabase db = TestDatabase.of("jdbc:h2:mem:pruebas");
 *      TestServer app = TestServer.start(Demo.app())) {
 *     db.migrate(Migrations.from(Path.of("db/migraciones")));
 *     app.client().get("/usuarios").ok();
 * }
 * }</pre>
 *
 * <p>Necesita {@code cero-data} y el driver JDBC de la base en el classpath de pruebas; en
 * {@code cero-test} los dos son opcionales, porque una aplicación sin base de datos no tiene
 * por qué arrastrarlos.
 */
public final class TestDatabase implements AutoCloseable {

    private final String source;
    private final Pool pool;

    private TestDatabase(String source, String jdbcUrl) {
        this.source = source;
        this.pool = Pool.to(jdbcUrl).build();
        DataSources.register(source, pool);
    }

    /** Registra la base como origen por omisión: la aplicación bajo prueba la usa sin tocar nada. */
    public static TestDatabase of(String jdbcUrl) {
        return new TestDatabase(DataSources.DEFAULT, jdbcUrl);
    }

    public static TestDatabase of(String source, String jdbcUrl) {
        return new TestDatabase(source, jdbcUrl);
    }

    public String source() {
        return source;
    }

    public Pool pool() {
        return pool;
    }

    /**
     * Aplica el lote entero contra esta base y devuelve cuántas migraciones corrieron.
     *
     * <p>Falla con el nombre de la migración rota, que es el dato que hace falta: una prueba que
     * solo dice «la base no está» obliga a reproducir el fallo a mano.
     */
    public int migrate(Migrations migrations) {
        Migrations.Verificacion resultado = migrations.verify(source);
        if (!resultado.correcta()) {
            throw new AssertionError("las migraciones no aplican sobre una base vacía: "
                    + resultado.resumen());
        }
        return resultado.aplicadas();
    }

    @Override
    public void close() {
        pool.close();
    }
}
