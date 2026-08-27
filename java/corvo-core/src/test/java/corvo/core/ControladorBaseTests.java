package corvo.core;

import corvo.http.Server;

import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** La base opcional de controladores, y la carrera que no debe ocurrir. */
final class ControladorBaseTests {

    private ControladorBaseTests() {
    }

    static void run() throws Exception {
        Check.group("controlador base opcional");

        Server servidor = Corvo.app().port(0)
                .controllers(ConBase.class, SinBase.class)
                .start();
        String base = "http://127.0.0.1:" + servidor.port();
        try {
            ayudas(base);
            convivencia(base);
            sinPeticion();
            aislamiento(base);
        } finally {
            servidor.stop();
        }
    }

    private static void ayudas(String base) throws Exception {
        HttpResponse<String> eco = Cliente.get(base + "/base/eco/42?saludo=hola");
        Check.equal("lee la variable de ruta sin recibir el Context", eco.statusCode(), 200);
        Check.that("y la variable de consulta", eco.body().contains("42") && eco.body().contains("hola"));

        HttpResponse<String> ruta = Cliente.get(base + "/base/ruta");
        Check.equal("sabe en qué ruta está", ruta.body(), "/base/ruta");

        HttpResponse<String> json = Cliente.get(base + "/base/json");
        Check.that("json() responde application/json",
                json.headers().firstValue("content-type").orElse("").startsWith("application/json"));

        HttpResponse<String> ida = Cliente.get(base + "/base/ida");
        Check.equal("redirect() responde 302", ida.statusCode(), 302);
        Check.equal("y al destino que se le dijo", ida.headers().firstValue("location").orElse(""), "/base/ruta");

        HttpResponse<String> roto = Cliente.get(base + "/base/roto");
        Check.equal("fallo() corta con el código que se le pasa", roto.statusCode(), 418);
    }

    private static void convivencia(String base) throws Exception {
        Check.equal("un controlador sin heredar sigue funcionando igual",
                Cliente.get(base + "/plano/eco/7").body(), "7");
    }

    private static void sinPeticion() {
        Check.that("fuera de una petición no hay contexto", !Current.present());
        Check.raises("y preguntarlo falla en vez de devolver el de otro",
                IllegalStateException.class, Current::context);
    }

    /**
     * La prueba que justifica que esto no sea un campo del controlador. Del controlador hay una
     * sola instancia; si guardara la petición dentro, con 200 peticiones a la vez alguna
     * respondería con el identificador de otra.
     */
    private static void aislamiento(String base) throws Exception {
        int peticiones = 200;
        var respuestas = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(peticiones);

        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < peticiones; i++) {
                int mio = i;
                hilos.execute(() -> {
                    try {
                        salida.await();
                        respuestas.add(Cliente.get(base + "/base/eco/" + mio + "?saludo=x").body());
                    } catch (Exception fallo) {
                        respuestas.add("fallo: " + fallo);
                    } finally {
                        fin.countDown();
                    }
                });
            }
            salida.countDown();
            Check.that("las 200 peticiones simultáneas terminan", fin.await(30, TimeUnit.SECONDS));
        }

        Set<String> vistos = new HashSet<>();
        boolean todasDistintas = true;
        for (String cuerpo : respuestas) {
            if (!vistos.add(cuerpo)) {
                todasDistintas = false;
            }
        }
        Check.equal("llegan las 200 respuestas", respuestas.size(), peticiones);
        Check.that("y cada una trae su propio identificador, sin pisarse", todasDistintas);
    }

    // ── controladores de prueba ──────────────────────────────────────────────

    @Route("/base")
    public static class ConBase extends Controller {

        @Get("/eco/{id}")
        public Result eco() {
            return text(param("id") + ":" + query("saludo"));
        }

        @Get("/ruta")
        public Result donde() {
            return text(path());
        }

        @Get("/json")
        public Result datos() {
            return json(Map.of("ok", true));
        }

        @Get("/ida")
        public Result ida() {
            return redirect("/base/ruta");
        }

        @Get("/roto")
        public Result roto() {
            throw fallo(418, "soy una tetera");
        }
    }

    @Route("/plano")
    public static class SinBase {

        @Get("/eco/{id}")
        public Result eco(Context contexto) {
            return Result.text(contexto.pathVariable("id"));
        }
    }
}
