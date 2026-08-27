package corvo.core;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trabajo fuera de la petición: lanzar y olvidar, esperar un resultado, repetir cada tanto o
 * seguir una expresión cron. Una sola puerta para las cuatro cosas.
 *
 * <pre>{@code
 * Tasks tareas = Tasks.start();
 * tareas.run(() -> correo.enviar(aviso));               // y olvidarse
 * tareas.every(Duration.ofMinutes(5), () -> sondear()); // cada tanto
 * tareas.cron("0 3 * * *", () -> respaldo());           // a las 3 de la mañana
 * }</pre>
 *
 * <p>Cada tarea corre en su propio hilo virtual, así que bloquearse dentro no cuesta un hilo del
 * sistema. {@link Cron} es solo el tipo que interpreta la expresión; no hace falta tocarlo.
 */
public final class Tasks implements AutoCloseable {

    private static final Log log = Log.of(Tasks.class);

    private final ExecutorService trabajadores = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService reloj;
    private final List<Programada> programadas = new CopyOnWriteArrayList<>();
    private final AtomicLong lanzadas = new AtomicLong();
    private final AtomicLong fallidas = new AtomicLong();

    private volatile boolean abierto = true;

    private Tasks() {
        reloj = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "corvo-tasks");
            hilo.setDaemon(true);
            return hilo;
        });
    }

    public static Tasks start() {
        return new Tasks();
    }

    /** Lanza y olvida, sobre un hilo virtual. Los fallos se registran, no se propagan. */
    public void run(Runnable tarea) {
        requerirAbierto();
        lanzadas.incrementAndGet();
        trabajadores.execute(() -> ejecutar("tarea", tarea));
    }

    /** Lanza y devuelve el resultado futuro, para cuando sí importa qué pasó. */
    public <T> CompletableFuture<T> submit(Callable<T> tarea) {
        requerirAbierto();
        lanzadas.incrementAndGet();
        CompletableFuture<T> promesa = new CompletableFuture<>();
        trabajadores.execute(() -> {
            try {
                promesa.complete(tarea.call());
            } catch (Exception fallo) {
                fallidas.incrementAndGet();
                promesa.completeExceptionally(fallo);
            }
        });
        return promesa;
    }

    public Cancelacion after(Duration retraso, Runnable tarea) {
        requerirAbierto();
        var futuro = reloj.schedule(() -> run(tarea), retraso.toMillis(), TimeUnit.MILLISECONDS);
        return () -> futuro.cancel(false);
    }

    public Cancelacion every(Duration intervalo, Runnable tarea) {
        return every(intervalo, intervalo, tarea);
    }

    public Cancelacion every(Duration primera, Duration intervalo, Runnable tarea) {
        requerirAbierto();
        var futuro = reloj.scheduleAtFixedRate(() -> run(tarea),
                primera.toMillis(), intervalo.toMillis(), TimeUnit.MILLISECONDS);
        return () -> futuro.cancel(false);
    }

    /** Programa por expresión cron de cinco campos, con resolución de un minuto. */
    public Cancelacion cron(String expresion, Runnable tarea) {
        requerirAbierto();
        Programada programada = new Programada(Cron.of(expresion), tarea);
        programadas.add(programada);
        asegurarLatido();
        return () -> {
            programada.viva.set(false);
            programadas.remove(programada);
        };
    }

    public long lanzadas() {
        return lanzadas.get();
    }

    public long fallidas() {
        return fallidas.get();
    }

    public int programadas() {
        return programadas.size();
    }

    public boolean open() {
        return abierto;
    }

    @Override
    public void close() {
        if (!abierto) {
            return;
        }
        abierto = false;
        reloj.shutdownNow();
        trabajadores.shutdown();
        try {
            if (!trabajadores.awaitTermination(5, TimeUnit.SECONDS)) {
                trabajadores.shutdownNow();
            }
        } catch (InterruptedException cortado) {
            Thread.currentThread().interrupt();
            trabajadores.shutdownNow();
        }
    }

    private final AtomicBoolean latiendo = new AtomicBoolean();

    private void asegurarLatido() {
        if (!latiendo.compareAndSet(false, true)) {
            return;
        }
        long hastaElMinuto = 60_000 - (System.currentTimeMillis() % 60_000);
        reloj.scheduleAtFixedRate(this::revisarCron, hastaElMinuto, 60_000, TimeUnit.MILLISECONDS);
    }

    private void revisarCron() {
        LocalDateTime ahora = LocalDateTime.now();
        for (Programada programada : programadas) {
            if (programada.viva.get() && programada.cron.coincide(ahora)) {
                run(programada.tarea);
            }
        }
    }

    private void ejecutar(String nombre, Runnable tarea) {
        try {
            tarea.run();
        } catch (RuntimeException fallo) {
            fallidas.incrementAndGet();
            log.error("falló una {} en segundo plano", nombre);
            log.error(fallo.getClass().getSimpleName(), fallo);
        }
    }

    private void requerirAbierto() {
        if (!abierto) {
            throw new IllegalStateException("el planificador está cerrado");
        }
    }

    @FunctionalInterface
    public interface Cancelacion {
        void cancel();
    }

    private record Programada(Cron cron, Runnable tarea, AtomicBoolean viva) {
        Programada(Cron cron, Runnable tarea) {
            this(cron, tarea, new AtomicBoolean(true));
        }
    }
}
