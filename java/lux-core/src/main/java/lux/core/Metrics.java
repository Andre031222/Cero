package lux.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class Metrics implements Middleware {

    private static final long[] TOPES = {1, 2, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, Long.MAX_VALUE};

    private final Map<String, Acumulado> porRuta = new ConcurrentHashMap<>();
    private final List<String> ignoradas = new ArrayList<>();
    private final AtomicLong arranque = new AtomicLong(System.currentTimeMillis());

    private Metrics() {
    }

    public static Metrics enabled() {
        return new Metrics();
    }

    public Metrics ignore(String... prefijos) {
        ignoradas.addAll(List.of(prefijos));
        return this;
    }

    public Endpoint endpoint() {
        return context -> Result.raw(toJson());
    }

    public Endpoint prometheusEndpoint() {
        return context -> Result.text(toPrometheus()).header("Content-Type", "text/plain; version=0.0.4");
    }

    @Override
    public Object handle(Context context, Chain chain) throws Exception {
        if (ignorada(context.path())) {
            return chain.proceed(context);
        }
        long inicio = System.nanoTime();
        int estado = 200;
        try {
            Object salida = chain.proceed(context);
            estado = context.response().status();
            return salida;
        } catch (lux.http.HttpException fallo) {
            estado = fallo.status();
            throw fallo;
        } catch (Exception fallo) {
            estado = 500;
            throw fallo;
        } finally {
            registrar(clave(context), estado, (System.nanoTime() - inicio) / 1_000_000);
        }
    }

    public void registrar(String clave, int estado, long millis) {
        porRuta.computeIfAbsent(clave, ignorado -> new Acumulado()).sumar(estado, millis);
    }

    public void reset() {
        porRuta.clear();
        arranque.set(System.currentTimeMillis());
    }

    public int rutas() {
        return porRuta.size();
    }

    public Resumen snapshot() {
        long total = 0;
        long errores = 0;
        long suma = 0;
        List<Ruta> rutas = new ArrayList<>(porRuta.size());

        for (Map.Entry<String, Acumulado> entrada : porRuta.entrySet()) {
            Acumulado a = entrada.getValue();
            long n = a.cuenta.get();
            total += n;
            errores += a.errores.get();
            suma += a.totalMillis.get();
            rutas.add(a.aRuta(entrada.getKey()));
        }
        rutas.sort((izq, der) -> Long.compare(der.peticiones(), izq.peticiones()));

        return new Resumen(total, errores,
                total == 0 ? 0 : redondear((double) suma / total),
                System.currentTimeMillis() - arranque.get(),
                rutas);
    }

    public String toJson() {
        return Json.write(snapshot());
    }

    public String toPrometheus() {
        Resumen resumen = snapshot();
        StringBuilder salida = new StringBuilder(512);

        salida.append("# HELP lux_requests_total Peticiones atendidas\n");
        salida.append("# TYPE lux_requests_total counter\n");
        for (Ruta ruta : resumen.rutas()) {
            salida.append("lux_requests_total{ruta=\"").append(escapar(ruta.ruta())).append("\"} ")
                    .append(ruta.peticiones()).append('\n');
        }

        salida.append("# HELP lux_request_errors_total Respuestas con estado 5xx o 4xx\n");
        salida.append("# TYPE lux_request_errors_total counter\n");
        for (Ruta ruta : resumen.rutas()) {
            salida.append("lux_request_errors_total{ruta=\"").append(escapar(ruta.ruta())).append("\"} ")
                    .append(ruta.errores()).append('\n');
        }

        salida.append("# HELP lux_request_duration_ms Latencia por ruta en milisegundos\n");
        salida.append("# TYPE lux_request_duration_ms summary\n");
        for (Ruta ruta : resumen.rutas()) {
            String etiqueta = escapar(ruta.ruta());
            salida.append("lux_request_duration_ms{ruta=\"").append(etiqueta).append("\",quantile=\"0.5\"} ")
                    .append(ruta.p50()).append('\n');
            salida.append("lux_request_duration_ms{ruta=\"").append(etiqueta).append("\",quantile=\"0.95\"} ")
                    .append(ruta.p95()).append('\n');
            salida.append("lux_request_duration_ms{ruta=\"").append(etiqueta).append("\",quantile=\"0.99\"} ")
                    .append(ruta.p99()).append('\n');
        }

        salida.append("# HELP lux_uptime_ms Tiempo desde el arranque\n");
        salida.append("# TYPE lux_uptime_ms gauge\n");
        salida.append("lux_uptime_ms ").append(resumen.tiempoActivoMillis()).append('\n');
        return salida.toString();
    }

    private boolean ignorada(String ruta) {
        for (String prefijo : ignoradas) {
            if (ruta.startsWith(prefijo)) {
                return true;
            }
        }
        return false;
    }

    private static String clave(Context context) {
        String patron = context.routed() ? context.route().pattern().raw() : context.path();
        return context.method() + " " + patron;
    }

    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static double redondear(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }

    public record Resumen(long peticiones, long errores, double mediaMillis,
                          long tiempoActivoMillis, List<Ruta> rutas) {
    }

    public record Ruta(String ruta, long peticiones, long errores,
                       long minMillis, long maxMillis, double mediaMillis,
                       long p50, long p95, long p99) {
    }

    private static final class Acumulado {

        private final AtomicLong cuenta = new AtomicLong();
        private final AtomicLong errores = new AtomicLong();
        private final AtomicLong totalMillis = new AtomicLong();
        private final AtomicLong minMillis = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxMillis = new AtomicLong();
        private final AtomicLongArray cubos = new AtomicLongArray(TOPES.length);

        void sumar(int estado, long millis) {
            cuenta.incrementAndGet();
            totalMillis.addAndGet(millis);
            if (estado >= 400) {
                errores.incrementAndGet();
            }
            minMillis.accumulateAndGet(millis, Math::min);
            maxMillis.accumulateAndGet(millis, Math::max);

            for (int i = 0; i < TOPES.length; i++) {
                if (millis <= TOPES[i]) {
                    cubos.incrementAndGet(i);
                    return;
                }
            }
        }

        Ruta aRuta(String clave) {
            long n = cuenta.get();
            return new Ruta(clave, n, errores.get(),
                    n == 0 ? 0 : minMillis.get(), maxMillis.get(),
                    n == 0 ? 0 : redondear((double) totalMillis.get() / n),
                    percentil(n, 0.50), percentil(n, 0.95), percentil(n, 0.99));
        }

        long percentil(long total, double fraccion) {
            if (total == 0) {
                return 0;
            }
            long objetivo = (long) Math.ceil(total * fraccion);
            long acumulado = 0;
            for (int i = 0; i < TOPES.length; i++) {
                acumulado += cubos.get(i);
                if (acumulado >= objetivo) {
                    return TOPES[i] == Long.MAX_VALUE ? maxMillis.get() : TOPES[i];
                }
            }
            return maxMillis.get();
        }
    }
}
