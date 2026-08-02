package lux.view;

public final class TestSuite {

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        RenderTests.run();
        LayoutTests.run();
        IntegrationTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
