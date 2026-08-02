package lux.data;

public final class TestSuite {

    private TestSuite() {
    }

    public static void main(String[] args) {
        ValueTests.run();
        DbTests.run();
        PoolTests.run();
        RepositoryTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
