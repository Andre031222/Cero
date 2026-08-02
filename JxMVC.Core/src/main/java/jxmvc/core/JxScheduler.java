/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  v3.1.1   : R. Andre Vilca Solorzano — cron expressions

package jxmvc.core;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Motor de tareas programadas — sin dependencias externas.
 * Detecta métodos {@code @JxScheduled} en servicios {@code @JxService}
 * y los ejecuta con {@link ScheduledExecutorService}.
 *
 * <pre>
 *   &#64;JxService
 *   public class ReportService {
 *
 *       &#64;JxScheduled(fixedDelay = 60_000, initialDelay = 5_000)
 *       public void purgeExpiredTokens() { ... }
 *
 *       &#64;JxScheduled(fixedRate = 30_000)
 *       public void refreshCache() { ... }
 *
 *       &#64;JxScheduled(cron = "0 3 * * *")       // cada día a las 3:00 AM
 *       public void backupDiario() { ... }
 *
 *       &#64;JxScheduled(cron = "0 9 * * 1")       // cada lunes a las 9:00 AM
 *       public void reporteSemanal() { ... }
 *
 *       &#64;JxScheduled(cron = "&#42;/15 * * * *")   // cada 15 minutos
 *       public void syncExterno() { ... }
 *
 *       &#64;JxScheduled(cron = "0 0 1 * *")       // primer día de cada mes
 *       public void facturacion() { ... }
 *   }
 * </pre>
 *
 * Se inicia automáticamente en {@code MainLxServlet.init()}.
 * También se puede invocar manualmente:
 * <pre>
 *   JxScheduler.start();                                         // escanea servicios registrados
 *   JxScheduler.scheduleCron(() -> backup(), "0 2 * * *");       // cron directo
 *   JxScheduler.scheduleAtFixedRate(() -> ping(), 0, 60_000);    // fixed rate directo
 *   JxScheduler.shutdown();                                      // libera el executor
 * </pre>
 */
public final class JxScheduler {

    private static final AtomicInteger threadId = new AtomicInteger(1);
    private static final JxLogger      log      = JxLogger.getLogger(JxScheduler.class);

    private static final ScheduledExecutorService exec =
            Executors.newScheduledThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors()),
                    r -> {
                        Thread t = new Thread(r, "jx-scheduler-" + threadId.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
            );

    private JxScheduler() {}

    // ── Inicio ────────────────────────────────────────────────────────────

    /**
     * Escanea todos los singletons de {@link JxServiceRegistry} y programa
     * los métodos anotados con {@code @JxScheduled}.
     * Llamar una sola vez desde {@code MainLxServlet.init()}.
     */
    public static void start() {
        for (Object service : JxServiceRegistry.values()) {
            scanAndSchedule(service);
        }
    }

    /**
     * Programa explícitamente un método con sus parámetros de scheduling.
     * Gestiona automáticamente cron, fixedRate y fixedDelay.
     */
    public static void schedule(Object target, Method method, JxMapping.JxScheduled ann) {
        method.setAccessible(true);
        Runnable task = () -> {
            try { method.invoke(target); }
            catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("@JxScheduled {}.{} falló: {}",
                        target.getClass().getSimpleName(), method.getName(), cause.getMessage());
            }
        };
        String cron = ann.cron().trim();
        if (!cron.isEmpty()) {
            scheduleCron(task, cron);
            return;
        }
        long initial = ann.initialDelay();
        if (ann.fixedDelay() > 0) {
            exec.scheduleWithFixedDelay(task, initial, ann.fixedDelay(), TimeUnit.MILLISECONDS);
        } else if (ann.fixedRate() > 0) {
            exec.scheduleAtFixedRate(task, initial, ann.fixedRate(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Programa una tarea con expresión cron de 5 campos.
     * La tarea se reprograma automáticamente después de cada ejecución.
     *
     * <pre>
     *   JxScheduler.scheduleCron(() -> backup(), "0 2 * * *");   // cada día a las 2 AM
     *   JxScheduler.scheduleCron(() -> report(), "0 8 * * 1");   // cada lunes a las 8 AM
     *   JxScheduler.scheduleCron(() -> ping(),   "&#42;/5 * * * *"); // cada 5 minutos
     * </pre>
     *
     * Campos: {@code minuto hora día-del-mes mes día-de-semana} (dow: 0=domingo, 6=sábado).
     */
    public static void scheduleCron(Runnable task, String cron) {
        CronTrigger trigger = new CronTrigger(cron);
        fireCron(task, cron, trigger);
    }

    /**
     * Programa una tarea con tasa fija.
     *
     * @param task      tarea a ejecutar
     * @param initialMs milisegundos antes del primer disparo
     * @param periodMs  milisegundos entre disparos
     */
    public static void scheduleAtFixedRate(Runnable task, long initialMs, long periodMs) {
        exec.scheduleAtFixedRate(() -> {
            try { task.run(); }
            catch (Exception e) { log.warn("scheduleAtFixedRate tarea falló: {}", e.getMessage()); }
        }, initialMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Programa una tarea con retardo fijo entre ejecuciones.
     *
     * @param task      tarea a ejecutar
     * @param initialMs milisegundos antes del primer disparo
     * @param delayMs   milisegundos entre el fin de una ejecución y el inicio de la siguiente
     */
    public static void scheduleWithFixedDelay(Runnable task, long initialMs, long delayMs) {
        exec.scheduleWithFixedDelay(() -> {
            try { task.run(); }
            catch (Exception e) { log.warn("scheduleWithFixedDelay tarea falló: {}", e.getMessage()); }
        }, initialMs, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Ejecuta una tarea una sola vez después del retraso dado.
     *
     * <pre>
     *   JxScheduler.runOnce(() -> notificarAdmin(), 5_000); // en 5 segundos
     * </pre>
     */
    public static void runOnce(Runnable task, long delayMs) {
        exec.schedule(() -> {
            try { task.run(); }
            catch (Exception e) { log.warn("runOnce tarea falló: {}", e.getMessage()); }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Ejecuta una tarea inmediatamente en el pool del scheduler. */
    public static void runAsync(Runnable task) {
        exec.submit(() -> {
            try { task.run(); }
            catch (Exception e) { log.warn("runAsync tarea falló: {}", e.getMessage()); }
        });
    }

    /** Detiene el executor esperando hasta 10s para tareas en curso (graceful shutdown). */
    public static void shutdown() {
        exec.shutdown();
        try { exec.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        finally { if (!exec.isTerminated()) exec.shutdownNow(); }
    }

    /** Retorna {@code true} si el scheduler está activo. */
    public static boolean isRunning() {
        return !exec.isShutdown();
    }

    // ── Cron ─────────────────────────────────────────────────────────────

    private static void fireCron(Runnable task, String cron, CronTrigger trigger) {
        long delay = trigger.nextDelayMs();
        exec.schedule(() -> {
            try   { task.run(); }
            catch (Exception e) { log.warn("cron \"{}\" falló: {}", cron, e.getMessage()); }
            finally { fireCron(task, cron, trigger); }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Parsea y evalúa expresiones cron de 5 campos.
     * Campos: {@code min(0-59) hora(0-23) dom(1-31) mes(1-12) dow(0-6, 0=domingo)}.
     * Operadores: {@code *} cualquiera, valor exacto, {@code * /N} cada N (sin espacio en uso real),
     *             {@code A-B} rango, {@code A,B,C} lista.
     */
    static final class CronTrigger {

        private final int[] minutes;    // 0-59
        private final int[] hours;      // 0-23
        private final int[] doms;       // 1-31  (day-of-month)
        private final int[] months;     // 1-12
        private final int[] dows;       // 0-6   (0=domingo)

        CronTrigger(String expr) {
            String[] p = expr.trim().split("\\s+");
            if (p.length != 5)
                throw new IllegalArgumentException("Cron debe tener 5 campos: \"" + expr + "\"");
            minutes = parseField(p[0],  0, 59);
            hours   = parseField(p[1],  0, 23);
            doms    = parseField(p[2],  1, 31);
            months  = parseField(p[3],  1, 12);
            dows    = parseField(p[4],  0,  6);
        }

        /** Milisegundos desde ahora hasta el próximo disparo. */
        long nextDelayMs() {
            LocalDateTime now  = LocalDateTime.now();
            LocalDateTime next = nextFire(now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1));
            return Duration.between(now, next).toMillis();
        }

        private LocalDateTime nextFire(LocalDateTime from) {
            LocalDateTime t     = from;
            LocalDateTime limit = from.plusYears(4);
            while (t.isBefore(limit)) {
                if (!has(months, t.getMonthValue())) {
                    t = t.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                    continue;
                }
                int dow = t.getDayOfWeek().getValue() % 7;   // SUNDAY=7 → 0; MON=1…SAT=6
                if (!has(doms, t.getDayOfMonth()) || !has(dows, dow)) {
                    t = t.plusDays(1).withHour(0).withMinute(0);
                    continue;
                }
                if (!has(hours, t.getHour())) {
                    t = t.plusHours(1).withMinute(0);
                    continue;
                }
                if (!has(minutes, t.getMinute())) {
                    t = t.plusMinutes(1);
                    continue;
                }
                return t;
            }
            throw new IllegalStateException("Cron sin próximo disparo en 4 años desde: " + from);
        }

        private static boolean has(int[] arr, int val) {
            for (int a : arr) if (a == val) return true;
            return false;
        }

        private static int[] parseField(String f, int lo, int hi) {
            if ("*".equals(f)) return range(lo, hi);
            if (f.startsWith("*/")) {
                int step = Integer.parseInt(f.substring(2));
                List<Integer> vals = new ArrayList<>();
                for (int i = lo; i <= hi; i += step) vals.add(i);
                return vals.stream().mapToInt(x -> x).toArray();
            }
            if (f.contains(",")) {
                return Arrays.stream(f.split(",")).mapToInt(Integer::parseInt).toArray();
            }
            if (f.contains("-")) {
                String[] parts = f.split("-", 2);
                return range(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            return new int[]{ Integer.parseInt(f) };
        }

        private static int[] range(int from, int to) {
            int[] arr = new int[to - from + 1];
            for (int i = 0; i < arr.length; i++) arr[i] = from + i;
            return arr;
        }
    }

    // ── Privados ──────────────────────────────────────────────────────────

    private static void scanAndSchedule(Object target) {
        for (Method m : target.getClass().getMethods()) {
            JxMapping.JxScheduled ann = m.getAnnotation(JxMapping.JxScheduled.class);
            if (ann == null) continue;
            boolean hasCron  = !ann.cron().isBlank();
            boolean hasTimer = ann.fixedDelay() > 0 || ann.fixedRate() > 0;
            if (!hasCron && !hasTimer) continue;
            schedule(target, m, ann);
        }
    }
}
