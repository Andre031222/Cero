package cero.http;

public final class TestSuite {

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        ProtocolTests.run();
        ProtocolTests.trasProxy();
        LimitsTests.run();
        TlsTests.run();
        CookieTests.run();
        SessionTests.run();
        SessionTests.almacenCompartido();
        MultipartTests.run();
        GzipTests.run();
        StaticFilesTests.run();
        HostileTests.run();
        WebSocketTests.run();
        ConformidadTests.run();

        SseTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
