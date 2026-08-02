/// JxMVC benchmarks — generador de carga HTTP en JDK puro (sin dependencias).
/// Compilar: javac LoadClient.java
/// Ejecutar: java LoadClient <url> <conexiones> <segundos> [warmupSegs]
///   ej: java LoadClient http://localhost:8080/plaintext 64 30 5
/// Reporta: throughput (req/s), latencia media y percentiles p50/p90/p95/p99,
/// errores y no-2xx, en una línea CSV apta para agregación.

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

public class LoadClient {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("uso: java LoadClient <url> <conexiones> <segundos> [warmupSegs]");
            System.exit(2);
        }
        String url      = args[0];
        int    conns    = Integer.parseInt(args[1]);
        long   durSecs  = Long.parseLong(args[2]);
        long   warmup   = args.length > 3 ? Long.parseLong(args[3]) : 5;

        // Warmup: deja al JIT alcanzar estado estacionario antes de medir.
        if (warmup > 0) {
            System.err.println("warmup " + warmup + "s ...");
            runPhase(url, conns, warmup, false);
        }

        System.err.println("midiendo " + durSecs + "s con " + conns + " conexiones ...");
        Result r = runPhase(url, conns, durSecs, true);

        long   count = r.count.get();
        double rps   = count / (r.elapsedNanos / 1e9);
        long[] lat   = r.latenciesNanosSorted();
        System.err.printf("req=%d  errores=%d  no2xx=%d  rps=%.0f%n", count, r.errors.get(), r.non2xx.get(), rps);
        // CSV: url,conns,durSecs,requests,errors,non2xx,rps,meanMs,p50Ms,p90Ms,p95Ms,p99Ms
        System.out.printf("%s,%d,%d,%d,%d,%d,%.1f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                url, conns, durSecs, count, r.errors.get(), r.non2xx.get(), rps,
                r.meanMs(), pctMs(lat, 50), pctMs(lat, 90), pctMs(lat, 95), pctMs(lat, 99));
    }

    static Result runPhase(String url, int conns, long durSecs, boolean record) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();

        Result res = new Result();
        long deadline = System.nanoTime() + durSecs * 1_000_000_000L;
        CountDownLatch done = new CountDownLatch(conns);
        List<Thread> threads = new ArrayList<>();

        long start = System.nanoTime();
        for (int i = 0; i < conns; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (System.nanoTime() < deadline) {
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                            long dt = System.nanoTime() - t0;
                            if (record) {
                                res.count.increment();
                                res.latencies.add(dt);
                                if (resp.statusCode() < 200 || resp.statusCode() >= 300) res.non2xx.increment();
                            }
                        } catch (Exception e) {
                            if (record) res.errors.increment();
                        }
                    }
                } finally { done.countDown(); }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        done.await();
        res.elapsedNanos = System.nanoTime() - start;
        return res;
    }

    static double pctMs(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx] / 1e6;
    }

    static final class Result {
        final Counter count  = new Counter();
        final Counter errors = new Counter();
        final Counter non2xx = new Counter();
        final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long elapsedNanos;

        long[] latenciesNanosSorted() {
            long[] a = latencies.stream().mapToLong(Long::longValue).toArray();
            java.util.Arrays.sort(a);
            return a;
        }
        double meanMs() {
            long sum = 0; int n = 0;
            for (long v : latencies) { sum += v; n++; }
            return n == 0 ? 0 : (sum / (double) n) / 1e6;
        }
    }

    /** Contador simple respaldado por AtomicLong con API mínima. */
    static final class Counter {
        final AtomicLong v = new AtomicLong();
        void increment() { v.incrementAndGet(); }
        public String toString() { return Long.toString(v.get()); }
        long get() { return v.get(); }
    }
}
