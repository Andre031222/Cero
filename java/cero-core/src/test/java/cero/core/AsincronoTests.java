package cero.core;

import cero.http.ErrorReporter;
import cero.http.Server;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class AsincronoTests {

    private AsincronoTests() {
    }

    record Mensaje(String rol, String contenido) {
    }

    record Peticion(String modelo, List<Mensaje> mensajes) {
    }

    static void run() throws Exception {
        Check.group("cliente HTTP");
        clienteHttp();

        Check.group("cron");
        cron();

        Check.group("trabajo en segundo plano");
        trabajos();
    }

    private static void clienteHttp() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        AtomicInteger llamadas = new AtomicInteger();

        Server servidor = Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .routes(r -> r
                        .get("/eco", ctx -> Map.of("q", ctx.query("q"), "n", ctx.query("n")))
                        .post("/chat", ctx -> {
                            recibido.set(ctx.bodyText());
                            return Map.of("respuesta", "hola");
                        })
                        .get("/cabecera", ctx -> ctx.header("Authorization"))
                        .post("/formulario", ctx -> ctx.form("campo"))
                        .get("/inestable", ctx -> {
                            if (llamadas.incrementAndGet() < 3) {
                                throw new cero.http.HttpException(503, "todavía no");
                            }
                            return "por fin";
                        })
                        .get("/roto", ctx -> {
                            throw new cero.http.HttpException(404, "no está");
                        })
                        .get("/flujo", ctx -> {
                            ctx.response().header("Content-Type", "text/event-stream");
                            try (var salida = ctx.response().stream()) {
                                salida.write("data: uno\n\n".getBytes());
                                salida.write("data: dos\n\n".getBytes());
                                salida.write("data: [DONE]\n\n".getBytes());
                            }
                            return null;
                        }))
                .start();
        try {
            String base = "http://127.0.0.1:" + servidor.port();

            Http.Respuesta eco = Http.to(base + "/eco").query("q", "lux").query("n", 42).get();
            Check.that("GET con parámetros de consulta", eco.ok());
            Check.that("los parámetros llegan codificados",
                    eco.cuerpo().contains("\"q\":\"lux\"") && eco.cuerpo().contains("\"n\":\"42\""));
            Check.equal("el estado se expone", eco.estado(), 200);
            Check.that("y las cabeceras de respuesta",
                    eco.cabecera("Content-Type").startsWith("application/json"));

            Check.equal("la consulta se añade a una URL que ya tiene ?",
                    Http.to("http://x/a?ya=1").query("b", "2").direccionCompleta(),
                    "http://x/a?ya=1&b=2");
            Check.that("los valores se escapan",
                    Http.to("http://x/a").query("q", "a b&c").direccionCompleta().endsWith("q=a+b%26c"));

            Http.Respuesta chat = Http.to(base + "/chat")
                    .bearer("clave-secreta")
                    .post(new Peticion("modelo-x", List.of(new Mensaje("user", "hola"))));
            Check.that("POST serializa el objeto a JSON", chat.ok());
            Check.that("con la forma esperada",
                    recibido.get().contains("\"modelo\":\"modelo-x\"")
                            && recibido.get().contains("\"rol\":\"user\""));

            Check.equal("bearer() pone la cabecera",
                    Http.to(base + "/cabecera").bearer("abc123").get().cuerpo(), "Bearer abc123");

            Check.equal("postForm codifica como formulario",
                    Http.to(base + "/formulario").postForm(Map.of("campo", "con espacio")).cuerpo(),
                    "con espacio");

            record Salida(String respuesta) { }
            Check.equal("as() vincula la respuesta a un record",
                    Http.to(base + "/chat").post(Map.of("x", 1)).as(Salida.class),
                    new Salida("hola"));

            llamadas.set(0);
            Check.equal("retry insiste ante 5xx",
                    Http.to(base + "/inestable").retry(3).retryDelay(Duration.ofMillis(20)).get().cuerpo(),
                    "por fin");
            Check.equal("y llamó las veces necesarias", llamadas.get(), 3);

            Http.Respuesta rota = Http.to(base + "/roto").get();
            Check.equal("un 404 se devuelve, no se lanza", rota.estado(), 404);
            Check.that("ok() lo distingue", !rota.ok());
            Check.raises("requerido() sí lanza", Http.HttpClientException.class, rota::requerido);

            List<String> eventos = new ArrayList<>();
            Http.to(base + "/flujo").sse(null, eventos::add);
            Check.equal("sse entrega cada bloque de datos", eventos, List.of("uno", "dos"));
            Check.that("y [DONE] cierra el flujo sin entregarse", !eventos.contains("[DONE]"));
        } finally {
            servidor.stop();
        }
    }

    private static void cron() {
        Cron cadaMinuto = Cron.of("* * * * *");
        Check.that("* coincide siempre", cadaMinuto.coincide(LocalDateTime.of(2026, 8, 2, 3, 27)));

        Cron medianoche = Cron.of("0 0 * * *");
        Check.that("a medianoche sí", medianoche.coincide(LocalDateTime.of(2026, 8, 2, 0, 0)));
        Check.that("a las 00:01 no", !medianoche.coincide(LocalDateTime.of(2026, 8, 2, 0, 1)));

        Cron cadaQuince = Cron.of("*/15 * * * *");
        Check.that("paso: minuto 0", cadaQuince.coincide(LocalDateTime.of(2026, 8, 2, 9, 0)));
        Check.that("paso: minuto 30", cadaQuince.coincide(LocalDateTime.of(2026, 8, 2, 9, 30)));
        Check.that("paso: minuto 7 no", !cadaQuince.coincide(LocalDateTime.of(2026, 8, 2, 9, 7)));

        Cron laborables = Cron.of("30 8 * * 1-5");
        Check.that("lunes a las 8:30 sí",
                laborables.coincide(LocalDateTime.of(2026, 8, 3, 8, 30)));
        Check.that("domingo no", !laborables.coincide(LocalDateTime.of(2026, 8, 2, 8, 30)));

        Cron domingo = Cron.of("0 12 * * 0");
        Check.that("el domingo es 0", domingo.coincide(LocalDateTime.of(2026, 8, 2, 12, 0)));
        Check.that("y también 7",
                Cron.of("0 12 * * 7").coincide(LocalDateTime.of(2026, 8, 2, 12, 0)));

        Cron lista = Cron.of("0,30 9,18 * * *");
        Check.that("listas en minuto y hora", lista.coincide(LocalDateTime.of(2026, 8, 2, 18, 30)));
        Check.that("y descarta lo que no está", !lista.coincide(LocalDateTime.of(2026, 8, 2, 10, 30)));

        Cron añoNuevo = Cron.of("0 0 1 1 *");
        Check.equal("siguiente() encuentra el próximo instante",
                añoNuevo.siguiente(LocalDateTime.of(2026, 8, 2, 12, 0)),
                LocalDateTime.of(2027, 1, 1, 0, 0));
        Check.equal("y no devuelve el actual si ya pasó",
                cadaMinuto.siguiente(LocalDateTime.of(2026, 8, 2, 9, 0)),
                LocalDateTime.of(2026, 8, 2, 9, 1));

        Check.raises("cuatro campos se rechaza", IllegalArgumentException.class,
                () -> Cron.of("* * * *"));
        Check.raises("minuto 99 se rechaza", IllegalArgumentException.class,
                () -> Cron.of("99 * * * *"));
        Check.raises("texto en vez de número se rechaza", IllegalArgumentException.class,
                () -> Cron.of("lunes * * * *"));
        Check.raises("paso cero se rechaza", IllegalArgumentException.class,
                () -> Cron.of("*/0 * * * *"));
        Check.raises("rango al revés se rechaza", IllegalArgumentException.class,
                () -> Cron.of("30-10 * * * *"));
    }

    private static void trabajos() throws Exception {
        try (Tasks jobs = Tasks.start()) {
            CountDownLatch hecho = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                jobs.run(hecho::countDown);
            }
            Check.that("run() ejecuta en segundo plano", hecho.await(5, TimeUnit.SECONDS));
            Check.that("y lleva la cuenta", jobs.lanzadas() >= 3);

            Check.equal("submit() devuelve el resultado",
                    jobs.submit(() -> 6 * 7).get(5, TimeUnit.SECONDS), 42);

            var falla = jobs.submit(() -> {
                throw new IllegalStateException("a propósito");
            });
            boolean propago = false;
            try {
                falla.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException esperado) {
                propago = esperado.getCause() instanceof IllegalStateException;
            }
            Check.that("submit() propaga el fallo al futuro", propago);

            CountDownLatch tras = new CountDownLatch(1);
            jobs.after(Duration.ofMillis(120), tras::countDown);
            Check.that("after() dispara pasado el retraso", tras.await(5, TimeUnit.SECONDS));

            AtomicInteger repeticiones = new AtomicInteger();
            var cancelar = jobs.every(Duration.ofMillis(60), repeticiones::incrementAndGet);
            Thread.sleep(400);
            cancelar.cancel();
            int alCancelar = repeticiones.get();
            Check.that("every() repite (" + alCancelar + " veces)", alCancelar >= 3);
            Thread.sleep(250);
            Check.that("y al cancelar deja de repetir", repeticiones.get() <= alCancelar + 1);

            Check.equal("cron() se registra", programadasTras(jobs, "*/5 * * * *"), 1);

            AtomicInteger fallos = new AtomicInteger();
            Log.Nivel previo = Log.nivel();
            Log.nivel(Log.Nivel.NADA);
            try {
                CountDownLatch corrio = new CountDownLatch(1);
                jobs.run(() -> {
                    corrio.countDown();
                    throw new IllegalStateException("revienta");
                });
                Check.that("una tarea que falla no derriba el planificador",
                        corrio.await(5, TimeUnit.SECONDS));
                Thread.sleep(150);
                Check.that("y queda contabilizada", jobs.fallidas() >= 1);
                fallos.set(1);
            } finally {
                Log.nivel(previo);
            }
            Check.equal("el planificador sigue vivo tras el fallo",
                    jobs.submit(() -> "sigo").get(5, TimeUnit.SECONDS), "sigo");
        }

        Tasks cerrado = Tasks.start();
        cerrado.close();
        Check.that("un planificador cerrado lo declara", !cerrado.open());
        Check.raises("y rechaza tareas nuevas", IllegalStateException.class,
                () -> cerrado.run(() -> { }));
        cerrado.close();
        Check.that("cerrar dos veces no falla", true);
    }

    private static int programadasTras(Tasks jobs, String expresion) {
        jobs.cron(expresion, () -> { });
        return jobs.programadas();
    }
}
