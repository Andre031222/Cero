package lux.core;

import lux.http.ErrorReporter;
import lux.http.Server;

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
                throw new lux.http.HttpException(404, "no existe");
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

        Check.group("log de acceso");
        acceso();
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
        Metrics metricas = Metrics.enabled().ignore("/lux/");

        Server servidor = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .use(metricas)
                .controllers(TiendaController.class)
                .routes(r -> r.get("/lux/metrics", metricas.endpoint())
                              .get("/lux/metrics/prometheus", metricas.prometheusEndpoint()))
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
                    resumen.rutas().stream().noneMatch(r -> r.ruta().contains("/lux/")));

            String json = Cliente.get(base + "/lux/metrics").body();
            Check.that("el endpoint JSON expone el total", json.contains("\"peticiones\":"));
            Check.that("y el detalle por ruta", json.contains("GET /tienda"));
            Check.equal("con tipo JSON",
                    Cliente.get(base + "/lux/metrics").headers().firstValue("content-type").orElse(null),
                    "application/json");

            String prometheus = Cliente.get(base + "/lux/metrics/prometheus").body();
            Check.that("Prometheus declara el tipo de métrica",
                    prometheus.contains("# TYPE lux_requests_total counter"));
            Check.that("expone el contador por ruta",
                    prometheus.contains("lux_requests_total{ruta=\"GET /tienda\"} 2"));
            Check.that("y los cuantiles", prometheus.contains("quantile=\"0.99\""));
            Check.that("y el tiempo activo", prometheus.contains("lux_uptime_ms"));

            metricas.reset();
            Check.equal("reset vacía el registro", metricas.snapshot().peticiones(), 0L);
        } finally {
            servidor.stop();
        }
    }

    private static void acceso() throws Exception {
        List<String> lineas = new ArrayList<>();

        Server servidor = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .use(AccessLog.combined().to(lineas::add).ignore("/salud"))
                .controllers(TiendaController.class)
                .routes(r -> r.get("/salud", ctx -> "ok"))
                .start();
        try {
            String base = "http://127.0.0.1:" + servidor.port();

            Cliente.get(base + "/tienda");
            Check.equal("registra una línea por petición", lineas.size(), 1);
            Check.that("con el verbo y la ruta", lineas.get(0).contains("\"GET /tienda HTTP/1.1\""));
            Check.that("y el estado", lineas.get(0).contains("\" 200 "));
            Check.that("y el usuario anónimo como guion", lineas.get(0).contains(" - - ["));

            lineas.clear();
            Cliente.get(base + "/tienda/999");
            Check.that("registra también los errores", lineas.get(0).contains("\" 404 "));

            lineas.clear();
            Cliente.get(base + "/tienda/roto");
            Check.that("y las excepciones no controladas", lineas.get(0).contains("\" 500 "));

            lineas.clear();
            Cliente.get(base + "/salud");
            Check.equal("las rutas ignoradas no se registran", lineas.size(), 0);

            lineas.clear();
            Cliente.get(base + "/tienda?buscar=zapato");
            Check.that("conserva la cadena de consulta",
                    lineas.get(0).contains("/tienda?buscar=zapato"));
        } finally {
            servidor.stop();
        }

        List<String> compactas = new ArrayList<>();
        Server compacto = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .use(AccessLog.compact().to(compactas::add))
                .controllers(TiendaController.class)
                .start();
        try {
            Cliente.get("http://127.0.0.1:" + compacto.port() + "/tienda");
            Check.that("el formato compacto lleva el tiempo", compactas.get(0).contains(" ms"));
            Check.that("y la ruta", compactas.get(0).endsWith("/tienda"));
        } finally {
            compacto.stop();
        }
    }
}
