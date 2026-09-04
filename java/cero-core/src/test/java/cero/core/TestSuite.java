package cero.core;

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
        SanitizeTests.run();
        GuardTests.run();
        ObservabilidadTests.run();
        MensajesTests.run();
        TrazadoTests.run();
        LiveTests.run();
        AsincronoTests.run();
        AutenticacionTests.run();
        TransversalesTests.run();
        ControladorBaseTests.run();
        MailTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
