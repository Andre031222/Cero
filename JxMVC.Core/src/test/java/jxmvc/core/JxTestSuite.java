package jxmvc.core;

import java.lang.reflect.Method;
import java.util.*;

public class JxTestSuite {

    private static final List<String> SUITES = List.of(
        "jxmvc.core.CoreV3Test",
        "jxmvc.core.CorsSmokeTest",
        "jxmvc.core.CacheTest",
        "jxmvc.core.SchedulerCronTest",
        "jxmvc.core.JsonTest",
        "jxmvc.core.ValidationExtTest",
        "jxmvc.core.RateLimiterTest",
        "jxmvc.core.MetricsTest",
        "jxmvc.core.SanitizerTest",
        "jxmvc.core.PoolTest",
        "jxmvc.core.GzipTest",
        "jxmvc.core.WebSocketTest",
        "jxmvc.core.SecurityTest",
        "jxmvc.core.HardeningV33Test",
        "jxmvc.core.OAuthTest"
    );

    public static void main(String[] args) throws Exception {
        int totalPassed = 0, totalFailed = 0;
        List<String> failures = new ArrayList<>();

        for (String suite : SUITES) {
            try {
                Class<?> cls = Class.forName(suite);
                // Reset counters (getDeclaredField para campos package-private)
                try { var fP = cls.getDeclaredField("passed"); fP.setAccessible(true); fP.set(null, 0); } catch (Exception ignored) {}
                try { var fF = cls.getDeclaredField("failed"); fF.setAccessible(true); fF.set(null, 0); } catch (Exception ignored) {}

                Method m = cls.getMethod("main", String[].class);
                m.invoke(null, (Object) new String[0]);

                int p = 0, f = 0;
                try { var fP = cls.getDeclaredField("passed"); fP.setAccessible(true); p = (int) fP.get(null); } catch (Exception ignored) {}
                try { var fF = cls.getDeclaredField("failed"); fF.setAccessible(true); f = (int) fF.get(null); } catch (Exception ignored) {}

                totalPassed += p;
                totalFailed += f;
                String name = cls.getSimpleName();
                System.out.printf("  %-24s  pass=%-3d fail=%d%n", name, p, f);
                if (f > 0) failures.add(name);

            } catch (ClassNotFoundException e) {
                System.out.printf("  %-24s  SKIP (no encontrado)%n", suite.substring(suite.lastIndexOf('.') + 1));
            } catch (Exception e) {
                System.out.printf("  %-24s  ERROR: %s%n", suite, e.getMessage());
                totalFailed++;
                failures.add(suite);
            }
        }

        System.out.println("─".repeat(50));
        System.out.printf("  TOTAL  pass=%d  fail=%d%n", totalPassed, totalFailed);
        if (!failures.isEmpty()) System.out.println("  FALLOS: " + failures);
        System.exit(totalFailed > 0 ? 1 : 0);
    }
}
