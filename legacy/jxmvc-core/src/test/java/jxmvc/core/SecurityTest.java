package jxmvc.core;

public class SecurityTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String label) { passed++; System.out.println("  OK  " + label); }
    static void fail(String label, String msg) { failed++; System.out.println("  FAIL " + label + ": " + msg); }
    static void check(String label, boolean cond) { if (cond) ok(label); else fail(label, "false"); }

    public static void main(String[] args) {
        testJsonError();
        testSafeMessage();
        testSafeViewPath();
        testSanitizeFileName();
        testMassAssignment();
        testHttpSchemeValidation();
        System.out.printf("SecurityTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testJsonError() {
        check("comillas escapadas",
                MainLxServlet.jsonError(400, "rota \"aqui\"")
                        .equals("{\"error\":400,\"message\":\"rota \\\"aqui\\\"\"}"));
        check("backslash escapado",
                MainLxServlet.jsonError(500, "c:\\tmp")
                        .equals("{\"error\":500,\"message\":\"c:\\\\tmp\"}"));
        check("salto de línea escapado",
                MainLxServlet.jsonError(500, "a\nb")
                        .equals("{\"error\":500,\"message\":\"a\\nb\"}"));
        check("null produce mensaje por defecto",
                MainLxServlet.jsonError(500, null)
                        .equals("{\"error\":500,\"message\":\"Error\"}"));
    }

    static void testSafeMessage() {
        check("dev conserva el mensaje",
                "detalle".equals(MainLxServlet.safeMessage("detalle", "generico", false)));
        check("prod oculta el mensaje",
                "generico".equals(MainLxServlet.safeMessage("detalle", "generico", true)));
        check("mensaje vacío usa fallback",
                "generico".equals(MainLxServlet.safeMessage("", "generico", false)));
        check("mensaje null usa fallback",
                "generico".equals(MainLxServlet.safeMessage(null, "generico", true)));
    }

    static void testSafeViewPath() {
        check("ruta normal pasa",            MainLxServlet.safeViewPath("home/index"));
        check("ruta absoluta jsp pasa",      MainLxServlet.safeViewPath("/WEB-INF/views/home/index.jsp"));
        check("traversal .. bloqueado",      !MainLxServlet.safeViewPath("../../web.xml"));
        check("traversal interno bloqueado", !MainLxServlet.safeViewPath("home/../../secreto"));
        check("backslash bloqueado",         !MainLxServlet.safeViewPath("home\\index"));
        check("doble slash bloqueado",       !MainLxServlet.safeViewPath("//etc/passwd"));
        check("null bloqueado",              !MainLxServlet.safeViewPath(null));
    }

    static void testSanitizeFileName() {
        check("CRLF neutralizado",
                !JxResponse.sanitizeFileName("a\r\nX-Header: inj.txt").contains("\r")
             && !JxResponse.sanitizeFileName("a\r\nX-Header: inj.txt").contains("\n"));
        check("comillas neutralizadas",
                !JxResponse.sanitizeFileName("a\"b.txt").contains("\""));
        check("separadores neutralizados",
                !JxResponse.sanitizeFileName("..\\..\\a/b.txt").contains("/")
             && !JxResponse.sanitizeFileName("..\\..\\a/b.txt").contains("\\"));
        check("nombre normal intacto",
                "reporte-2026.pdf".equals(JxResponse.sanitizeFileName("reporte-2026.pdf")));
        check("null produce default",
                "download".equals(JxResponse.sanitizeFileName(null)));
    }

    public static class BindTarget {
        public String name;
        public final String role = "USER";
        public static String shared = "original";
        public transient String secret = "none";
    }

    static void testMassAssignment() {
        String json = "{\"name\":\"ana\",\"role\":\"ADMIN\",\"shared\":\"hacked\",\"secret\":\"stolen\"}";
        BindTarget t = JxJson.fromJson(json, BindTarget.class);
        check("campo normal se asigna",     t != null && "ana".equals(t.name));
        check("campo final no se asigna",   t != null && "USER".equals(t.role));
        check("campo static no se asigna",  "original".equals(BindTarget.shared));
        check("campo transient no se asigna", t != null && "none".equals(t.secret));
    }

    static void testHttpSchemeValidation() {
        boolean rejected;
        try { JxHttp.builder("GET", "file:///etc/passwd"); rejected = false; }
        catch (IllegalArgumentException e) { rejected = true; }
        check("file:// rechazado", rejected);

        try { JxHttp.builder("GET", "gopher://x"); rejected = false; }
        catch (IllegalArgumentException e) { rejected = true; }
        check("gopher:// rechazado", rejected);

        try { JxHttp.builder("GET", "https://example.com"); rejected = false; }
        catch (IllegalArgumentException e) { rejected = true; }
        check("https:// aceptado", !rejected);
    }
}
