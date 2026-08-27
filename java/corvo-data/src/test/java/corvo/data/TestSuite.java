package corvo.data;

public final class TestSuite {

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
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
