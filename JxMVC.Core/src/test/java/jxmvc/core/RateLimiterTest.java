package jxmvc.core;

public class RateLimiterTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String l)            { passed++; System.out.println("  OK  " + l); }
    static void fail(String l, String m){ failed++; System.out.println("  FAIL " + l + ": " + m); }
    static void check(String l, boolean c){ if (c) ok(l); else fail(l, "false"); }

    static boolean allow(String client, String route, int limit, long windowSecs) {
        return JxRateLimiter.allow(client, route, limit, windowSecs);
    }

    public static void main(String[] args) throws Exception {
        testUnderLimit();
        testAtLimit();
        testOverLimit();
        testWindowReset();
        testSlidingEstimate();
        testSlidingRoll();
        System.out.printf("RateLimiterTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testUnderLimit() {
        String k = "ip1-" + System.nanoTime();
        check("under limit 1", allow(k, "GET:/test", 3, 60));
        check("under limit 2", allow(k, "GET:/test", 3, 60));
        check("under limit 3", allow(k, "GET:/test", 3, 60));
    }

    static void testAtLimit() {
        String k = "ip2-" + System.nanoTime();
        allow(k, "GET:/api", 2, 60);
        allow(k, "GET:/api", 2, 60);
        check("at limit blocked", !allow(k, "GET:/api", 2, 60));
    }

    static void testOverLimit() {
        String k = "ip3-" + System.nanoTime();
        for (int i = 0; i < 5; i++) allow(k, "POST:/x", 2, 60);
        check("over limit still blocked", !allow(k, "POST:/x", 2, 60));
    }

    static void testWindowReset() throws Exception {
        String k = "ip4-" + System.nanoTime();
        allow(k, "GET:/r", 1, 1);
        check("initially blocked", !allow(k, "GET:/r", 1, 1));
        Thread.sleep(1100);
        check("allowed after window reset", allow(k, "GET:/r", 1, 1));
    }

    // Prueba determinista de la estimación de ventana deslizante (sin timing real).
    // bucket = [conteoActual, inicioVentana, conteoPrevio, windowMs]
    static void testSlidingEstimate() {
        long W = 1000;
        // Previa=8, actual=2, transcurrido 250ms -> peso 0.75 -> 8*0.75 + 2 = 8.0
        double est = JxRateLimiter.estimatedUsage(new long[]{2, 0, 8, W}, 250, W);
        check("estimación deslizante arrastra la ventana previa", Math.abs(est - 8.0) < 1e-9);
        // Al final de la ventana -> peso 0 -> solo cuenta la actual
        double est2 = JxRateLimiter.estimatedUsage(new long[]{2, 0, 8, W}, W, W);
        check("estimación decae al conteo actual", Math.abs(est2 - 2.0) < 1e-9);
        // Inicio de ventana -> peso 1 -> arrastre completo de la previa
        double est3 = JxRateLimiter.estimatedUsage(new long[]{0, 0, 8, W}, 0, W);
        check("al inicio arrastra la previa completa", Math.abs(est3 - 8.0) < 1e-9);
    }

    static void testSlidingRoll() {
        long W = 1000;
        long[] b = {5, 0, 3, W};
        JxRateLimiter.roll(b, W, W);           // ventana inmediatamente siguiente
        check("roll ventana+1 arrastra actual->previa", b[2] == 5 && b[0] == 0 && b[1] == W);
        long[] b2 = {5, 0, 3, W};
        JxRateLimiter.roll(b2, 5 * W, W);      // hueco de varias ventanas
        check("roll con hueco resetea la previa", b2[2] == 0 && b2[0] == 0 && b2[1] == 5 * W);
        long[] b3 = {5, W, 3, W};
        JxRateLimiter.roll(b3, W, W);          // misma ventana: sin cambios
        check("roll misma ventana no altera", b3[0] == 5 && b3[2] == 3);
    }
}
