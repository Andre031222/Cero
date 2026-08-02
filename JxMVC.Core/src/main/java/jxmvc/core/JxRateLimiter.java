/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Limitador de tasa en memoria — motor para {@link JxMapping.JxRateLimit}.
 *
 * <p>Algoritmo <b>sliding window counter</b> (contador de ventana deslizante) por IP +
 * clave de ruta: se mantienen los conteos de la ventana alineada actual y de la anterior,
 * y el uso estimado es {@code prev·(1 − t/W) + actual}, donde {@code t} es lo transcurrido
 * dentro de la ventana actual y {@code W} su duración. Esto evita la ráfaga del doble del
 * límite que permite una ventana fija en el borde entre ventanas. Los contadores se limpian
 * automáticamente al expirar ambas ventanas.
 *
 * <pre>
 *   // Uso desde MainLxServlet (automático vía @JxRateLimit):
 *   if (!JxRateLimiter.allow(clientIp, "POST:/login", 10, 60)) {
 *       // 429 Too Many Requests
 *   }
 * </pre>
 */
public final class JxRateLimiter {

    private JxRateLimiter() {}

    /** Entrada: [conteoActual, inicioVentanaActual, conteoPrevio, windowMs] */
    private static final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    private static final java.util.concurrent.ScheduledExecutorService CLEANER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jx-ratelimiter-cleaner");
                t.setDaemon(true);
                return t;
            });

    static {
        // Daemon de limpieza: elimina solo buckets cuya propia ventana ya expiró
        CLEANER.scheduleWithFixedDelay(JxRateLimiter::cleanupExpired, 10, 10, TimeUnit.MINUTES);
    }

    /** Detiene el daemon de limpieza — llamar en el shutdown del contenedor. */
    public static void shutdown() {
        CLEANER.shutdownNow();
    }

    /**
     * Verifica y registra una petición.
     *
     * @param clientKey  identificador del cliente (ej. IP)
     * @param routeKey   clave de ruta (ej. {@code "POST:/api/login"})
     * @param maxReqs    número máximo de peticiones en la ventana
     * @param windowSecs duración de la ventana en segundos
     * @return {@code true} si la petición está permitida, {@code false} si supera el límite
     */
    public static boolean allow(String clientKey, String routeKey, int maxReqs, long windowSecs) {
        String key   = clientKey + "|" + routeKey;
        long   now   = System.currentTimeMillis();
        long   winMs = Math.max(1, windowSecs * 1000);
        long   winStart = (now / winMs) * winMs;

        long[] bucket = buckets.computeIfAbsent(key, k -> new long[]{0, winStart, 0, winMs});
        synchronized (bucket) {
            roll(bucket, winStart, winMs);
            if (estimatedUsage(bucket, now, winMs) >= maxReqs) return false;
            bucket[0]++;
            return true;
        }
    }

    /**
     * Retorna las peticiones restantes según la estimación de ventana deslizante.
     *
     * @return peticiones restantes (>= 0), o {@code maxReqs} si no hay registro
     */
    public static long remaining(String clientKey, String routeKey, int maxReqs, long windowSecs) {
        String key   = clientKey + "|" + routeKey;
        long   now   = System.currentTimeMillis();
        long   winMs = Math.max(1, windowSecs * 1000);
        long   winStart = (now / winMs) * winMs;
        long[] bucket = buckets.get(key);
        if (bucket == null) return maxReqs;
        synchronized (bucket) {
            roll(bucket, winStart, winMs);
            return Math.max(0, maxReqs - (long) Math.floor(estimatedUsage(bucket, now, winMs)));
        }
    }

    /** Avanza el bucket a la ventana alineada actual, arrastrando el conteo previo. */
    static void roll(long[] bucket, long winStart, long winMs) {
        long prevStart = bucket[1];
        if (winStart == prevStart) return;
        if (winStart - prevStart == winMs) {   // ventana inmediatamente siguiente
            bucket[2] = bucket[0];
        } else {                                // hueco de más de una ventana: sin arrastre
            bucket[2] = 0;
        }
        bucket[0] = 0;
        bucket[1] = winStart;
        bucket[3] = winMs;
    }

    /** Uso estimado = conteoPrevio·(1 − t/W) + conteoActual. */
    static double estimatedUsage(long[] bucket, long now, long winMs) {
        double elapsed = now - bucket[1];
        double weight  = Math.max(0.0, (winMs - elapsed) / (double) winMs);
        return bucket[2] * weight + bucket[0];
    }

    /** Limpia todos los buckets expirados. Llamar periódicamente para evitar memory leak. */
    public static void cleanup(long windowSecs) {
        long cutoff = System.currentTimeMillis() - windowSecs * 1000;
        buckets.entrySet().removeIf(e -> {
            long[] b = e.getValue();
            synchronized (b) { return b[1] < cutoff; }
        });
    }

    /** Elimina los buckets cuyas dos ventanas (actual y previa) ya expiraron (respeta ventanas largas activas). */
    static void cleanupExpired() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> {
            long[] b = e.getValue();
            synchronized (b) { return now - b[1] >= 2 * b[3]; }
        });
    }
}
