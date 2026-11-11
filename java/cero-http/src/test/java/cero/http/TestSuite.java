package cero.http;

final class TestSuite {

    void main() throws Exception {
        HpackTests.run();
        Http2Tests.run();
        PlazosTests.run();
        FuzzTests.run();
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
