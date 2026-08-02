package lux.http;

public final class TestSuite {

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        ProtocolTests.run();
        LimitsTests.run();
        TlsTests.run();
        CookieTests.run();
        SessionTests.run();
        MultipartTests.run();
        GzipTests.run();
        StaticFilesTests.run();
        HostileTests.run();

        Check.report();
        if (Check.failures() > 0) {
            System.exit(1);
        }
    }
}
