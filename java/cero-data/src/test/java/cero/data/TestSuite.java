package cero.data;

final class TestSuite {

    void main() throws Exception {
        ValueTests.run();
        DbTests.run();
        PoolTests.run();
        RepositoryTests.run();
        MotorTests.run();
        JdbcSessionsTests.run();
        MigrationsTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
