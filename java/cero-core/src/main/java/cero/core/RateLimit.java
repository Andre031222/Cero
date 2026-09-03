package cero.core;

import cero.http.HttpException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

public final class RateLimit implements Middleware {

    private static final Log log = Log.of(RateLimit.class);
    private static final int SWEEP_EVERY = 512;
    private static final int MAX_CLAVES = 100_000;

    private final int max;
    private final long windowMillis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    private Function<Context, String> key = context -> context.request().remoteAddress();
    private Predicate<Context> skip = context -> false;

    private RateLimit(int max, long windowMillis) {
        if (max < 1) {
            throw new IllegalArgumentException("el máximo debe ser al menos 1");
        }
        this.max = max;
        this.windowMillis = windowMillis;
    }

    public static RateLimit perMinute(int max) {
        return new RateLimit(max, Duration.ofMinutes(1).toMillis());
    }

    public static RateLimit perSecond(int max) {
        return new RateLimit(max, 1_000);
    }

    public static RateLimit of(int max, Duration window) {
        return new RateLimit(max, window.toMillis());
    }

    public RateLimit keyBy(Function<Context, String> value) {
        key = value;
        return this;
    }

    public RateLimit skipWhen(Predicate<Context> value) {
        skip = value;
        return this;
    }

    public int tracked() {
        return buckets.size();
    }

    public void reset() {
        buckets.clear();
    }

    @Override
    public Object handle(Context context, Chain chain) throws Exception {
        if (skip.test(context)) {
            return chain.proceed(context);
        }
        sweepOccasionally();

        // La ruta NO entra en la clave. Cuando entraba pasaban dos cosas malas: el mapa crecía
        // con cada ruta distinta que alguien inventara, y el límite era por ruta, así que
        // repartiendo la carga entre URLs se multiplicaba la cuota. Quien quiera granularidad
        // por ruta la pide con keyBy, que para eso está.
        String identity = key.apply(context);
        long now = System.currentTimeMillis();
        Bucket bucket = buckets.get(identity);
        if (bucket == null) {
            // Tope duro: por encima se deja pasar en vez de crecer sin freno. Con la ruta fuera
            // de la clave hacen falta MAX_CLAVES orígenes distintos para llegar aquí.
            if (buckets.size() >= MAX_CLAVES) {
                log.warn("rate limit: {} claves en seguimiento, no se admiten nuevas", buckets.size());
                return chain.proceed(context);
            }
            bucket = buckets.computeIfAbsent(identity, ignored -> new Bucket(now));
        }

        boolean allowed;
        double used;
        synchronized (bucket) {
            bucket.roll(now, windowMillis);
            used = bucket.estimate(now, windowMillis);
            allowed = used < max;
            if (allowed) {
                bucket.current++;
                used++;
            }
        }

        int remaining = allowed ? (int) Math.max(0, max - Math.ceil(used)) : 0;
        context.response()
                .header("X-RateLimit-Limit", String.valueOf(max))
                .header("X-RateLimit-Remaining", String.valueOf(remaining));

        if (!allowed) {
            long retryAfter = Math.max(1, (windowMillis - (now - bucket.start)) / 1_000);
            context.response().header("Retry-After", String.valueOf(retryAfter));
            throw new HttpException(429, "demasiadas peticiones; reintenta en " + retryAfter + " s");
        }
        return chain.proceed(context);
    }

    private void sweepOccasionally() {
        if (sinceSweep.incrementAndGet() < SWEEP_EVERY) {
            return;
        }
        sinceSweep.set(0);
        long cutoff = System.currentTimeMillis() - windowMillis * 2;
        buckets.values().removeIf(bucket -> bucket.start < cutoff);
    }

    private static final class Bucket {

        private long start;
        private int current;
        private int previous;

        Bucket(long start) {
            this.start = start;
        }

        void roll(long now, long windowMillis) {
            long elapsed = now - start;
            if (elapsed < windowMillis) {
                return;
            }
            if (elapsed < windowMillis * 2) {
                previous = current;
                current = 0;
                start += windowMillis;
                return;
            }
            previous = 0;
            current = 0;
            start = now;
        }

        double estimate(long now, long windowMillis) {
            double weight = 1.0 - (double) (now - start) / windowMillis;
            return previous * Math.max(0, weight) + current;
        }
    }
}
