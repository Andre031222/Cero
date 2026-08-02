package jxmvc.core;

public class MetricsTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String l)            { passed++; System.out.println("  OK  " + l); }
    static void fail(String l, String m){ failed++; System.out.println("  FAIL " + l + ": " + m); }
    static void check(String l, boolean c){ if (c) ok(l); else fail(l, "false"); }

    public static void main(String[] args) {
        testRecordAndJson();
        testErrors();
        testSlowRequest();
        System.out.printf("MetricsTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testRecordAndJson() {
        JxMetrics.record("/test", "GET", 200, 10);
        JxMetrics.record("/test", "GET", 200, 20);
        String json = JxMetrics.toJson();
        check("json not null",             json != null && !json.isBlank());
        check("json is object",            json.trim().startsWith("{"));
        check("totalRequests present",     json.contains("totalRequests"));
        check("avgDurationMs present",     json.contains("avgDurationMs") || json.contains("avg"));
    }

    static void testErrors() {
        long before = errorsFromJson(JxMetrics.toJson());
        JxMetrics.record("/fail", "POST", 500, 5);
        JxMetrics.record("/fail", "POST", 404, 5);
        long after = errorsFromJson(JxMetrics.toJson());
        check("error count increased", after >= before + 2);
    }

    static void testSlowRequest() {
        JxMetrics.record("/slow", "GET", 200, 9999);
        String json = JxMetrics.toJson();
        check("avgResponseMs present", json.contains("avgResponseMs"));
    }

    static long errorsFromJson(String json) {
        try {
            int idx = json.indexOf("totalErrors");
            if (idx < 0) return 0;
            int colon = json.indexOf(':', idx);
            int end   = json.indexOf(',', colon);
            if (end < 0) end = json.indexOf('}', colon);
            return Long.parseLong(json.substring(colon + 1, end).trim());
        } catch (Exception e) { return 0; }
    }
}
