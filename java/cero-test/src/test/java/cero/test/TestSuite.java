package cero.test;

public final class TestSuite {

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        ArranqueTests.run();
        SesionTests.run();
        BaseDeDatosTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
