package cero.core;

import cero.http.ErrorReporter;
import cero.http.Server;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

final class ObservabilidadTests {

    private ObservabilidadTests() {
    }

    @Route("/tienda")
    static final class TiendaController {

        @Get
        public Object index() {
            return "catálogo";
        }

        @Get("/{id}")
        public Object ver(@Path("id") int id) {
            if (id > 100) {
                throw new cero.http.HttpException(404, "no existe");
            }
            return "artículo " + id;
        }

        @Get("/lento")
        public Object lento() throws Exception {
            Thread.sleep(30);
            return "tardó";
        }

        @Get("/roto")
        public Object roto() {
            throw new IllegalStateException("se rompió");
        }
    }

    static void run() throws Exception {
        Check.group("log");
        registro();

        Check.group("métricas");
        metricas();
        metricasSinRuta();

        Check.group("log de acceso");
        acceso();
        estadoRealAunqueFalleElControlador();

        Check.group("salud");
        salud();
    }

    private static void salud() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean bdEnPie =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        Server servidor = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                .health(Health.checks()
                        .ready("bd", bdEnPie::get)
                        .ready("disco", () -> true))
                .start();
        String base = "http://127.0.0.1:" + servidor.port();
        try {
            HttpResponse<String> vivo = Cliente.get(base + "/cero/vivo");
            Check.equal("vivo responde 200", vivo.statusCode(), 200);
            Check.that("y dice cuánto lleva en pie", vivo.body().contains("activoMs"));

            HttpResponse<String> listo = Cliente.get(base + "/cero/listo");
            Check.equal("con todo en pie, listo responde 200", listo.statusCode(), 200);
            Check.that("y nombra cada comprobación", listo.body().contains("\"bd\":\"bien\""));

            bdEnPie.set(false);
            HttpResponse<String> caido = Cliente.get(base + "/cero/listo");
            Check.equal("con una comprobación caída, listo responde 503", caido.statusCode(), 503);
            Check.that("y dice cuál", caido.body().contains("\"bd\":\"mal\""));
            Check.that("sin ocultar las que sí van", caido.body().contains("\"disco\":\"bien\""));

            Check.equal("pero vivo sigue en 200: el proceso responde",
                    Cliente.get(base + "/cero/vivo").statusCode(), 200);

            Server conFallo = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                    .health(Health.checks().ready("remoto", () -> {
                        throw new IllegalStateException("no responde el host");
                    }))
                    .start();
            try {
                HttpResponse<String> r = Cliente.get("http://127.0.0.1:" + conFallo.port() + "/cero/listo");
                Check.equal("una comprobación que lanza da 503, no 500", r.statusCode(), 503);
                Check.that("y el mensaje llega entero", r.body().contains("no responde el host"));
            } finally {
                conFallo.stop();
            }

            Server aInternet = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                    .health(Health.checks().publico().ready("bd", () -> {
                        throw new IllegalStateException("jdbc:postgresql://10.0.0.7:5432/interna");
                    }))
                    .start();
            try {
                String suyo = "http://127.0.0.1:" + aInternet.port();
                HttpResponse<String> r = Cliente.get(suyo + "/cero/listo");
                Check.equal("en modo público el código sigue siendo 503", r.statusCode(), 503);
                Check.that("y el estado se dice", r.body().contains("no listo"));
                Check.that("pero no el mensaje del fallo", !r.body().contains("10.0.0.7"));
                Check.that("ni el nombre de la comprobación", !r.body().contains("\"bd\""));
                Check.that("ni la lista de comprobaciones", !r.body().contains("comprobaciones"));
                Check.equal("y vivo no cambia", Cliente.get(suyo + "/cero/vivo").statusCode(), 200);
            } finally {
                aInternet.stop();
            }

            Server publicoSano = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                    .health(Health.checks().publico().ready("bd", () -> true))
                    .start();
            try {
                HttpResponse<String> r = Cliente.get(
                        "http://127.0.0.1:" + publicoSano.port() + "/cero/listo");
                Check.equal("con todo bien, el modo público responde 200", r.statusCode(), 200);
                Check.that("diciendo solo que está listo",
                        r.body().contains("listo") && !r.body().contains("comprobaciones"));
            } finally {
                publicoSano.stop();
            }
        } finally {
            servidor.stop();
        }
    }

    private static String primeraLinea(List<String> registro) throws Exception {
        long limite = System.currentTimeMillis() + 3_000;
        while (registro.isEmpty() && System.currentTimeMillis() < limite) {
            Thread.sleep(10);
        }
        return registro.isEmpty() ? "" : registro.get(0);
    }

    private static void estadoRealAunqueFalleElControlador() throws Exception {
        List<String> lineas = new ArrayList<>();

        Server servidor = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                .use(AccessLog.combined().to(lineas::add))
                .routes(r -> r.get("/pagina", ctx -> {
                    throw new IllegalStateException("el controlador revienta");
                }))
                .start();
        try {
            var respuesta = Cliente.get("http://127.0.0.1:" + servidor.port() + "/pagina");
            Check.equal("el cliente recibe un 500", respuesta.statusCode(), 500);
            String linea = primeraLinea(lineas);
            Check.equal("y el log registra una línea", lineas.size(), 1);
            Check.that("con el 500, no el 200 que tenía antes de responder", linea.contains("\" 500 "));
        } finally {
            servidor.stop();
        }
    }

    private static void registro() {
        List<String> lineas = new ArrayList<>();
        Log.Nivel previo = Log.nivel();
        Log.destino(lineas::add);
        try {
            Log.nivel(Log.Nivel.DEBUG);
            Log log = Log.of("Prueba");

            log.info("arrancando en el puerto {}", 8080);
            Check.that("la línea lleva el nivel", lineas.get(0).contains("INFO"));
            Check.that("y el nombre", lineas.get(0).contains("Prueba"));
            Check.that("y el valor interpolado", lineas.get(0).endsWith("arrancando en el puerto 8080"));

            lineas.clear();
            log.debug("detalle {} y {}", "uno", 2);
            Check.that("interpola varios valores", lineas.get(0).endsWith("detalle uno y 2"));

            lineas.clear();
            Log.nivel(Log.Nivel.WARN);
            log.info("no debería salir");
            log.debug("tampoco");
            Check.equal("el nivel filtra por debajo", lineas.size(), 0);
            log.warn("esta sí");
            Check.equal("y deja pasar por encima", lineas.size(), 1);

            lineas.clear();
            Log.nivel(Log.Nivel.ERROR);
            log.error("falló al conectar", new IllegalStateException("sin ruta"));
            Check.that("un error con excepción incluye el tipo",
                    lineas.get(0).contains("IllegalStateException"));
            Check.that("y el mensaje de la excepción", lineas.get(0).contains("sin ruta"));

            lineas.clear();
            Log.nivel(Log.Nivel.NADA);
            log.error("silenciado");
            Check.equal("el nivel NADA calla todo", lineas.size(), 0);

            Check.equal("faltan valores: deja el marcador",
                    Log.interpolar("a {} b {}", new Object[]{1}), "a 1 b {}");
            Check.equal("sobran valores: se ignoran",
                    Log.interpolar("a {}", new Object[]{1, 2}), "a 1");
            Check.equal("sin marcadores devuelve tal cual",
                    Log.interpolar("sin nada", new Object[]{1}), "sin nada");

            Check.that("of() reutiliza la misma instancia", Log.of("X") == Log.of("X"));
        } finally {
            Log.nivel(previo);
            Log.destino(linea -> System.out.println(linea));
        }
    }

    private static void metricas() throws Exception {
        Metrics metricas = Metrics.enabled().ignore("/cero/");

        Server servidor = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                .use(metricas)
                .controllers(TiendaController.class)
                .routes(r -> r.get("/cero/metrics", metricas.endpoint())
                              .get("/cero/metrics/prometheus", metricas.prometheusEndpoint()))
                .start();
        try {
            String base = "http://127.0.0.1:" + servidor.port();

            Cliente.get(base + "/tienda");
            Cliente.get(base + "/tienda");
            Cliente.get(base + "/tienda/7");
            Cliente.get(base + "/tienda/999");
            Cliente.get(base + "/tienda/lento");
            Cliente.get(base + "/tienda/roto");

            Metrics.Resumen resumen = metricas.snapshot();
            Check.equal("cuenta todas las peticiones", resumen.peticiones(), 6L);
            Check.equal("y las que fallaron", resumen.errores(), 2L);
            Check.that("la media es positiva", resumen.mediaMillis() >= 0);
            Check.that("informa el tiempo activo", resumen.tiempoActivoMillis() >= 0);

            Check.equal("agrupa por patrón de ruta, no por URL", metricas.rutas(), 4);

            Metrics.Ruta masUsada = resumen.rutas().get(0);
            Check.equal("la ruta más usada va primera", masUsada.ruta(), "GET /tienda");
            Check.equal("con sus dos peticiones", masUsada.peticiones(), 2L);
            Check.equal("y sin errores", masUsada.errores(), 0L);

            Metrics.Ruta porId = resumen.rutas().stream()
                    .filter(r -> r.ruta().equals("GET /tienda/{id}")).findFirst().orElseThrow();
            Check.equal("la ruta con variable agrupa las dos llamadas", porId.peticiones(), 2L);
            Check.equal("y cuenta el 404 como error", porId.errores(), 1L);

            Metrics.Ruta lenta = resumen.rutas().stream()
                    .filter(r -> r.ruta().equals("GET /tienda/lento")).findFirst().orElseThrow();
            Check.that("mide la latencia de la ruta lenta", lenta.maxMillis() >= 25);
            Check.that("y sus percentiles", lenta.p95() >= 25);

            Check.that("las rutas ignoradas no se cuentan",
                    resumen.rutas().stream().noneMatch(r -> r.ruta().contains("/cero/")));

            String json = Cliente.get(base + "/cero/metrics").body();
            Check.that("el endpoint JSON expone el total", json.contains("\"peticiones\":"));
            Check.that("y el detalle por ruta", json.contains("GET /tienda"));
            Check.equal("con tipo JSON",
                    Cliente.get(base + "/cero/metrics").headers().firstValue("content-type").orElse(null),
                    "application/json");

            String prometheus = Cliente.get(base + "/cero/metrics/prometheus").body();
            Check.that("Prometheus declara el tipo de métrica",
                    prometheus.contains("# TYPE cero_requests_total counter"));
            Check.that("expone el contador por ruta",
                    prometheus.contains("cero_requests_total{ruta=\"GET /tienda\"} 2"));
            Check.that("y los cuantiles", prometheus.contains("quantile=\"0.99\""));
            Check.that("y el tiempo activo", prometheus.contains("cero_uptime_ms"));

            metricas.reset();
            Check.equal("reset vacía el registro", metricas.snapshot().peticiones(), 0L);
        } finally {
            servidor.stop();
        }
    }

    private static void metricasSinRuta() throws Exception {
        Metrics metricas = Metrics.enabled();
        Server servidor = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                .use(metricas)
                .controllers(TiendaController.class)
                .start();
        try {
            String base = "http://127.0.0.1:" + servidor.port();
            for (int n = 1; n <= 3; n++) {
                Cliente.get(base + "/no/existe/" + n);
            }
            List<Metrics.Ruta> rutas = metricas.snapshot().rutas();
            Check.equal("OBS-014: las peticiones sin ruta van a una sola serie", rutas.size(), 1);
            Check.equal("OBS-014: que se llama (sin ruta)", rutas.get(0).ruta(), "(sin ruta)");
            Check.equal("OBS-014: y cuenta las tres", rutas.get(0).peticiones(), 3L);
        } finally {
            servidor.stop();
        }
    }

    private static void acceso() throws Exception {
        List<String> lineas = new ArrayList<>();

        Server servidor = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                .use(AccessLog.combined().to(lineas::add).ignore("/salud"))
                .controllers(TiendaController.class)
                .routes(r -> r.get("/salud", ctx -> "ok"))
                .start();
        try {
            String base = "http://127.0.0.1:" + servidor.port();

            Cliente.get(base + "/tienda");
            String primera = primeraLinea(lineas);
            Check.equal("registra una línea por petición", lineas.size(), 1);
            Check.that("con el verbo y la ruta", primera.contains("\"GET /tienda HTTP/1.1\""));
            Check.that("y el estado", primera.contains("\" 200 "));
            Check.that("y el usuario anónimo como guion", primera.contains(" - - ["));

            lineas.clear();
            Cliente.get(base + "/tienda/999");
            Check.that("registra también los errores", primeraLinea(lineas).contains("\" 404 "));

            lineas.clear();
            Cliente.get(base + "/tienda/roto");
            Check.that("y las excepciones no controladas", primeraLinea(lineas).contains("\" 500 "));

            lineas.clear();
            Cliente.get(base + "/salud");
            Check.equal("las rutas ignoradas no se registran", lineas.size(), 0);

            lineas.clear();
            Cliente.get(base + "/tienda?buscar=zapato");
            Check.that("conserva la cadena de consulta",
                    primeraLinea(lineas).contains("/tienda?buscar=zapato"));
        } finally {
            servidor.stop();
        }

        List<String> compactas = new ArrayList<>();
        Server compacto = Cero.app().host("127.0.0.1").port(0).quiet().reporter(ErrorReporter.silent())
                .use(AccessLog.compact().to(compactas::add))
                .controllers(TiendaController.class)
                .start();
        try {
            Cliente.get("http://127.0.0.1:" + compacto.port() + "/tienda");
            String compacta = primeraLinea(compactas);
            Check.that("el formato compacto lleva el tiempo", compacta.contains(" ms"));
            Check.that("y la ruta", compacta.endsWith("/tienda"));
        } finally {
            compacto.stop();
        }
    }
}
