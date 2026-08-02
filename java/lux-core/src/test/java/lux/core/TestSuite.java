package lux.core;

public final class TestSuite {

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        JsonTests.run();
        RouterTests.run();
        RegistryTests.run();
        ConfigTests.run();
        DispatcherTests.run();
        ValidationTests.run();
        GuardTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
