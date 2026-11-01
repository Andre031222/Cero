package cero.test;

import cero.data.DataSources;
import cero.data.Migrations;
import cero.data.Pool;

public final class TestDatabase implements AutoCloseable {

    private final String source;
    private final Pool pool;

    private TestDatabase(String source, String jdbcUrl) {
        this.source = source;
        this.pool = Pool.to(jdbcUrl).build();
        DataSources.register(source, pool);
    }

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
