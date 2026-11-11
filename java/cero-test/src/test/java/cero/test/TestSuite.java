package cero.test;

final class TestSuite {

    void main() throws Exception {
        ArranqueTests.run();
        SesionTests.run();
        BaseDeDatosTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
