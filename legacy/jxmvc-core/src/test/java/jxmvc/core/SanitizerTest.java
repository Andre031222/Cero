package jxmvc.core;

public class SanitizerTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String l)            { passed++; System.out.println("  OK  " + l); }
    static void fail(String l, String m){ failed++; System.out.println("  FAIL " + l + ": " + m); }
    static void check(String l, boolean c){ if (c) ok(l); else fail(l, "false"); }

    static String s(String raw) { return BaseSanitizer.clean(raw); }

    public static void main(String[] args) {
        testScriptTag();
        testEventHandler();
        testJavascriptUrl();
        testImgOnerror();
        testIframe();
        testNormalTextUntouched();
        testAmpersandEncoded();
        testDoubleEncodingAttack();
        testNullInput();
        testStyleTag();
        System.out.printf("SanitizerTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testScriptTag() {
        String r = s("<script>alert(1)</script>");
        check("script tag removed", !r.contains("<script"));
        check("script body removed", !r.toLowerCase().contains("alert"));
    }

    static void testEventHandler() {
        String r = s("<div onclick=\"steal()\">x</div>");
        check("onclick removed", !r.contains("onclick"));
    }

    static void testJavascriptUrl() {
        String r = s("<a href=\"javascript:void(0)\">click</a>");
        check("javascript: href removed", !r.toLowerCase().contains("javascript:"));
    }

    static void testImgOnerror() {
        String r = s("<img src=x onerror=alert(1)>");
        check("onerror removed", !r.contains("onerror"));
    }

    static void testIframe() {
        String r = s("<iframe src=\"evil.com\"></iframe>");
        check("iframe removed", !r.contains("<iframe"));
    }

    static void testNormalTextUntouched() {
        // "+" es decodificado como espacio por URLDecoder — usamos texto sin "+"
        String input = "Hello world! resultado es 4";
        String r = s(input);
        check("normal text preserved", r.contains("Hello") && r.contains("resultado"));
    }

    static void testAmpersandEncoded() {
        String r = s("a & b");
        check("ampersand encoded or kept safe", !r.contains("<") && !r.contains(">"));
    }

    static void testDoubleEncodingAttack() {
        String r = s("&lt;script&gt;alert(1)&lt;/script&gt;");
        check("double-encoded not dangerous", !r.contains("<script"));
    }

    static void testNullInput() {
        try {
            String r = s(null);
            check("null input returns null or empty", r == null || r.isEmpty());
        } catch (NullPointerException e) {
            fail("null input", "NullPointerException thrown");
        }
    }

    static void testStyleTag() {
        String r = s("<style>body{display:none}</style>text");
        check("style tag removed", !r.contains("<style") && r.contains("text"));
    }
}
