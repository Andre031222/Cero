package jxmvc.core;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Verificaciones del hardening v3.3.0: CSRF, open redirect, body cap, router y métricas. */
public class HardeningV33Test {

    static int passed = 0;
    static int failed = 0;

    static void ok(String l)             { passed++; System.out.println("  OK  " + l); }
    static void fail(String l, String m) { failed++; System.out.println("  FAIL " + l + ": " + m); }
    static void check(String l, boolean c){ if (c) ok(l); else fail(l, "false"); }

    public static void main(String[] args) throws Exception {
        testCsrf();
        testRedirectGuard();
        testBodyCap();
        testMetricsMinMaxStatus();
        testRateLimiterLongWindows();
        testExtraArgsSegments();
        testMetricKeyCardinality();
        testHtmlEscape();
        System.out.printf("HardeningV33Test: pass=%d fail=%d%n", passed, failed);
    }

    // ── CSRF ──────────────────────────────────────────────────────────────

    static void testCsrf() {
        Map<String, Object> session = new ConcurrentHashMap<>();

        HttpServletRequest get = request("GET", session, null, null);
        String token = JxCsrf.token(get);
        check("csrf: token generado",        token != null && token.length() >= 32);
        check("csrf: token estable",         token.equals(JxCsrf.token(get)));
        check("csrf: field es input oculto", JxCsrf.field(get).contains("name=\"_csrf\"")
                                          && JxCsrf.field(get).contains(token));

        check("csrf: GET pasa sin token",    JxCsrf.validate(get));
        check("csrf: POST sin token falla",  !JxCsrf.validate(request("POST", session, null, null)));
        check("csrf: POST header ok",        JxCsrf.validate(request("POST", session, token, null)));
        check("csrf: POST campo ok",         JxCsrf.validate(request("POST", session, null, token)));
        check("csrf: token errado falla",    !JxCsrf.validate(request("POST", session, "x" + token, null)));
        check("csrf: sin sesión falla",      !JxCsrf.validate(request("POST", null, token, null)));
    }

    // ── Open redirect ─────────────────────────────────────────────────────

    static void testRedirectGuard() {
        check("redirect: relativo pasa",     "/inicio".equals(JxResponse.checkRedirect("/inicio")));
        check("redirect: null → /",          "/".equals(JxResponse.checkRedirect(null)));
        check("redirect: https bloqueado",   throws400(() -> JxResponse.checkRedirect("https://evil.com/p")));
        check("redirect: esquema bloqueado", throws400(() -> JxResponse.checkRedirect("javascript:alert(1)")));
        check("redirect: //host bloqueado",  throws400(() -> JxResponse.checkRedirect("//evil.com")));
    }

    static boolean throws400(Runnable r) {
        try { r.run(); return false; }
        catch (JxException e) { return e.getStatus() == 400; }
    }

    // ── Body cap ──────────────────────────────────────────────────────────

    static void testBodyCap() {
        String small = "{\"ok\":true}";
        JxRequest okReq = new JxRequest(bodyRequest(small, small.length()), null, new String[0]);
        check("body: normal se lee",         small.equals(okReq.body()));

        JxRequest declared = new JxRequest(
                bodyRequest("", 100L * 1024 * 1024), null, new String[0]);
        check("body: Content-Length grande → 413", throws413(declared::body));
    }

    static boolean throws413(Runnable r) {
        try { r.run(); return false; }
        catch (JxException e) { return e.getStatus() == 413; }
    }

    // ── Métricas ──────────────────────────────────────────────────────────

    static void testMetricsMinMaxStatus() {
        JxMetrics.record("/h33/min", "GET", 200, 10);
        JxMetrics.record("/h33/min", "GET", 200, 20);
        JxMetrics.record("/h33/min", "GET", 404, 30);
        String json = JxMetrics.toJson();
        int at = json.indexOf("GET:/h33/min");
        String route = at >= 0 ? json.substring(at, Math.min(json.length(), at + 120)) : "";
        check("metrics: minMs registrado",   route.contains("\"minMs\":10"));
        check("metrics: maxMs registrado",   route.contains("\"maxMs\":30"));
        check("metrics: clases de estado",   json.contains("\"2xx\":") && json.contains("\"4xx\":"));
    }

    // ── Rate limiter: ventanas largas sobreviven la limpieza ─────────────

    static void testRateLimiterLongWindows() {
        for (int i = 0; i < 3; i++) JxRateLimiter.allow("ipL", "GET:h33#larga", 5, 3600);
        JxRateLimiter.cleanupExpired();
        check("ratelimiter: ventana larga intacta tras limpieza",
                JxRateLimiter.remaining("ipL", "GET:h33#larga", 5, 3600) == 2);
    }

    // ── Router: extraArgs por segmentos ───────────────────────────────────

    static void testExtraArgsSegments() throws Exception {
        Object dispatcher = newDispatcher();
        Method m = dispatcher.getClass().getDeclaredMethod("extraArgs", String.class, String.class);
        m.setAccessible(true);

        String[] normal = (String[]) m.invoke(dispatcher, "/demo/salud/juan/12", "/demo/salud");
        check("router: args normales",       normal.length == 2 && "juan".equals(normal[0]) && "12".equals(normal[1]));

        String[] dobles = (String[]) m.invoke(dispatcher, "/demo//salud/juan", "/demo/salud");
        check("router: dobles slashes no desalinean", dobles.length == 1 && "juan".equals(dobles[0]));

        String[] mayus = (String[]) m.invoke(dispatcher, "/Demo/Salud/Juan", "/demo/salud");
        check("router: args conservan mayúsculas", mayus.length == 1 && "Juan".equals(mayus[0]));

        String[] codif = (String[]) m.invoke(dispatcher, "/demo/salud/mar%C3%ADa", "/demo/salud");
        check("router: args URL-decodificados", codif.length == 1 && "maría".equals(codif[0]));
    }

    static Object newDispatcher() throws Exception {
        Class<?> cls = Class.forName("jxmvc.core.BaseDispatcher");
        var ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    // ── metricKey: colapso de segmentos variables ─────────────────────────

    static void testMetricKeyCardinality() throws Exception {
        MainLxServlet servlet = new MainLxServlet();
        Method m = MainLxServlet.class.getDeclaredMethod("metricKey", String.class);
        m.setAccessible(true);
        check("metricKey: numéricos",  "/users/{n}".equals(m.invoke(servlet, "/users/123")));
        check("metricKey: uuid",       "/users/{n}/x".equals(
                m.invoke(servlet, "/users/550e8400-e29b-41d4-a716-446655440000/x")));
        check("metricKey: hash hex",   "/f/{n}".equals(m.invoke(servlet, "/f/deadbeefcafe1234")));
        check("metricKey: texto queda","/users/perfil".equals(m.invoke(servlet, "/users/perfil")));
    }

    // ── JxHtml ────────────────────────────────────────────────────────────

    static void testHtmlEscape() {
        check("esc: html neutralizado",
                "&lt;b&gt;&#39;x&#39;&quot;&amp;&#47;".equals(JxHtml.escape("<b>'x'\"&/")));
        check("esc: null → vacío", "".equals(JxHtml.escape(null)));
    }

    // ── Stubs ─────────────────────────────────────────────────────────────

    static HttpServletRequest request(String method, Map<String, Object> sessionAttrs,
                                      String headerToken, String paramToken) {
        HttpSession session = sessionAttrs == null ? null : session(sessionAttrs);
        InvocationHandler h = (proxy, m, args) -> switch (m.getName()) {
            case "getMethod"     -> method;
            case "getSession"    -> session;
            case "getHeader"     -> JxCsrf.HEADER.equals(args[0]) ? headerToken : null;
            case "getParameter"  -> JxCsrf.FIELD.equals(args[0]) ? paramToken : null;
            case "toString"      -> "StubRequest";
            case "hashCode"      -> System.identityHashCode(proxy);
            case "equals"        -> proxy == args[0];
            default              -> TestStubs.defaultValue(m.getReturnType());
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HardeningV33Test.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, h);
    }

    static HttpSession session(Map<String, Object> attrs) {
        InvocationHandler h = (proxy, m, args) -> switch (m.getName()) {
            case "getAttribute" -> attrs.get(args[0]);
            case "setAttribute" -> { attrs.put((String) args[0], args[1]); yield null; }
            case "toString"     -> "StubSession";
            case "hashCode"     -> System.identityHashCode(proxy);
            case "equals"       -> proxy == args[0];
            default             -> TestStubs.defaultValue(m.getReturnType());
        };
        return (HttpSession) Proxy.newProxyInstance(
                HardeningV33Test.class.getClassLoader(), new Class<?>[]{HttpSession.class}, h);
    }

    static HttpServletRequest bodyRequest(String content, long declaredLength) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        InvocationHandler h = (proxy, m, args) -> switch (m.getName()) {
            case "getContentLengthLong" -> declaredLength;
            case "getInputStream"       -> inputStream(bytes);
            case "toString"             -> "StubBodyRequest";
            case "hashCode"             -> System.identityHashCode(proxy);
            case "equals"               -> proxy == args[0];
            default                     -> TestStubs.defaultValue(m.getReturnType());
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HardeningV33Test.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, h);
    }

    static ServletInputStream inputStream(byte[] bytes) {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override public int read()                { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            @Override public boolean isFinished()      { return in.available() == 0; }
            @Override public boolean isReady()         { return true; }
            @Override public void setReadListener(ReadListener l) {}
        };
    }
}
