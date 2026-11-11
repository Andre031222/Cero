package cero.core;

final class TestSuite {

    void main() throws Exception {
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
