package corvo.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class TransversalesTests {

    private TransversalesTests() {
    }

    static void run() throws Exception {
        Check.group("caché");
        cacheBasico();
        cacheCaducidad();
        cacheTope();
        cacheCargaUnaSolaVez();
        cacheBackend();

        Check.group("bus de eventos");
        eventosPorLambda();
        eventosPorAnotacion();
        eventosPorSupertipo();
        eventoQueFalla();

        Check.group("perfiles");
        perfiles();

        Check.group("OpenAPI");
        openapi();
    }

    // ── OpenAPI ──────────────────────────────────────────────────────────────

    record Articulo(long id, String titulo, boolean publicado) {
    }

    record NuevoArticulo(String titulo) {
    }

    @Route("/api/articulos")
    static final class ArticuloController {

        @Get("/{id}")
        public Articulo porId(@Path("id") long id) {
            return new Articulo(id, "x", true);
        }

        @Get("")
        public List<Articulo> listar(@Query("pagina") int pagina) {
            return List.of();
        }

        @Post("")
        @RequireAuth
        public Articulo crear(@Body NuevoArticulo nuevo) {
            return new Articulo(1, nuevo.titulo(), false);
        }
    }

    private static void openapi() {
        Router router = new Router()
                .register(ArticuloController.class)
                .get("/salud", contexto -> "ok")
                .get("/informes/{anio}", contexto -> "informe");

        String json = OpenApi.describing(router)
                .title("Catálogo")
                .version("2.1.0")
                .server("https://api.local")
                .json();

        Object leido = Json.read(json);
        Check.that("la especificación es JSON válido", leido instanceof java.util.Map);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> raiz = (java.util.Map<String, Object>) leido;
        Check.equal("declara la versión de OpenAPI", raiz.get("openapi"), "3.0.3");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> info = (java.util.Map<String, Object>) raiz.get("info");
        Check.equal("con el título dado", info.get("title"), "Catálogo");
        Check.equal("y la versión dada", info.get("version"), "2.1.0");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> caminos = (java.util.Map<String, Object>) raiz.get("paths");
        Check.that("describe la ruta con variable", caminos.containsKey("/api/articulos/{id}"));
        Check.that("describe la ruta base", caminos.containsKey("/api/articulos"));
        Check.that("y también las declaradas con lambda", caminos.containsKey("/salud"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> articulos = (java.util.Map<String, Object>) caminos.get("/api/articulos");
        Check.that("la base tiene GET y POST", articulos.containsKey("get") && articulos.containsKey("post"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> crear = (java.util.Map<String, Object>) articulos.get("post");
        Check.that("el POST declara cuerpo", crear.containsKey("requestBody"));
        Check.that("y que exige autenticación", crear.containsKey("security"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> componentes = (java.util.Map<String, Object>) raiz.get("components");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> esquemas = (java.util.Map<String, Object>) componentes.get("schemas");
        Check.that("registra el record devuelto", esquemas.containsKey("Articulo"));
        Check.that("y el record del cuerpo", esquemas.containsKey("NuevoArticulo"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> articulo = (java.util.Map<String, Object>) esquemas.get("Articulo");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> propiedades = (java.util.Map<String, Object>) articulo.get("properties");
        Check.equal("con los tipos de sus componentes",
                ((java.util.Map<?, ?>) propiedades.get("id")).get("type"), "integer");
        Check.equal("incluidos los booleanos",
                ((java.util.Map<?, ?>) propiedades.get("publicado")).get("type"), "boolean");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> informe = (java.util.Map<String, Object>) caminos.get("/informes/{anio}");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> informeGet = (java.util.Map<String, Object>) informe.get("get");
        @SuppressWarnings("unchecked")
        List<Object> parametros = (List<Object>) informeGet.get("parameters");
        Check.equal("una lambda con variable declara su parámetro", parametros.size(), 1);
        Check.equal("y lo marca obligatorio",
                ((java.util.Map<?, ?>) parametros.get(0)).get("required"), true);

        Check.raises("sin router no se describe nada", IllegalArgumentException.class,
                () -> OpenApi.describing(null));
    }

    // ── caché ────────────────────────────────────────────────────────────────

    private static void cacheBasico() {
        Cache cache = Cache.named("prueba");
        Check.that("una caché nueva está vacía", cache.size() == 0);
        Check.that("lo que no está devuelve null", cache.fetch("x") == null);
        Check.that("y has() dice que no", !cache.has("x"));

        cache.put("x", "hola");
        Check.equal("lo guardado se recupera", cache.fetch("x"), "hola");
        Check.that("y has() dice que sí", cache.has("x"));
        Check.equal("con comprobación de tipo", cache.fetch("x", String.class), "hola");
        Check.that("de otro tipo devuelve null", cache.fetch("x", Integer.class) == null);

        cache.evict("x");
        Check.that("evict lo quita", cache.fetch("x") == null);

        cache.put("a", 1).put("b", 2);
        Check.equal("cuenta lo que tiene", cache.size(), 2);
        cache.clear();
        Check.equal("clear lo vacía", cache.size(), 0);

        Check.raises("una caché sin nombre no se crea", IllegalArgumentException.class,
                () -> Cache.named("  "));
    }

    private static void cacheCaducidad() throws Exception {
        Cache cache = Cache.named("caduca");
        cache.put("efimero", "ya no", Duration.ofMillis(60));
        Check.equal("antes de caducar sigue ahí", cache.fetch("efimero"), "ya no");
        Thread.sleep(120);
        Check.that("después de caducar ya no está", cache.fetch("efimero") == null);

        cache.put("eterno", "siempre");
        Thread.sleep(20);
        Check.equal("sin caducidad no caduca", cache.fetch("eterno"), "siempre");

        cache.put("otro", "x", Duration.ofMillis(30));
        Thread.sleep(60);
        Check.equal("lo caducado sigue ocupando sitio hasta barrer", cache.size(), 2);
        cache.sweep();
        Check.equal("sweep lo suelta", cache.size(), 1);
    }

    private static void cacheTope() {
        Cache cache = Cache.named("tope").maxEntries(10);
        for (int i = 0; i < 40; i++) {
            cache.put("clave" + i, i);
        }
        Check.that("nunca pasa del tope", cache.size() <= 10);
        Check.that("y lo último escrito sigue dentro", cache.fetch("clave39") != null);

        Cache sinTope = Cache.named("sinTope").maxEntries(0);
        for (int i = 0; i < 50; i++) {
            sinTope.put("clave" + i, i);
        }
        Check.equal("con tope 0 no desaloja nada", sinTope.size(), 50);
    }

    private static void cacheCargaUnaSolaVez() throws Exception {
        Cache cache = Cache.named("concurrente");
        AtomicInteger cargas = new AtomicInteger();
        int hilos = 16;
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch llegada = new CountDownLatch(hilos);

        for (int i = 0; i < hilos; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    salida.await();
                    cache.computeIfAbsent("caro", Duration.ofMinutes(1), () -> {
                        cargas.incrementAndGet();
                        Thread.sleep(30);
                        return "calculado";
                    });
                } catch (InterruptedException interrumpido) {
                    Thread.currentThread().interrupt();
                } finally {
                    llegada.countDown();
                }
            });
        }
        salida.countDown();
        Check.that("todos los hilos terminan", llegada.await(5, TimeUnit.SECONDS));
        Check.equal("16 hilos a la vez cargan una sola vez", cargas.get(), 1);
        Check.equal("y todos ven el mismo valor", cache.fetch("caro"), "calculado");

        Check.raises("un fallo en la carga sale tal cual", IllegalStateException.class,
                () -> cache.computeIfAbsent("roto", Duration.ZERO, () -> {
                    throw new IllegalStateException("no se pudo");
                }));
        Check.that("y lo roto no se cachea", cache.fetch("roto") == null);
    }

    private static void cacheBackend() {
        List<String> llamadas = new ArrayList<>();
        Cache cache = Cache.named("externa").backedBy(new Cache.Backend() {

            private final java.util.Map<String, Object> remoto = new java.util.HashMap<>();

            @Override
            public void put(String nombre, String clave, Object valor, long segundos) {
                llamadas.add("put " + nombre + "/" + clave + " ttl=" + segundos);
                remoto.put(clave, valor);
            }

            @Override
            public Object fetch(String nombre, String clave) {
                return remoto.get(clave);
            }

            @Override
            public boolean has(String nombre, String clave) {
                return remoto.containsKey(clave);
            }

            @Override
            public void evict(String nombre, String clave) {
                remoto.remove(clave);
            }

            @Override
            public void clear(String nombre) {
                remoto.clear();
            }
        });

        cache.put("k", "v", Duration.ofSeconds(30));
        Check.equal("con backend, escribe fuera", llamadas.get(0), "put externa/k ttl=30");
        Check.equal("y lee de fuera", cache.fetch("k"), "v");
        Check.that("has() también", cache.has("k"));
        Check.equal("sin guardar nada en memoria", cache.size(), 0);
        cache.evict("k");
        Check.that("evict llega al backend", !cache.has("k"));
    }

    // ── eventos ──────────────────────────────────────────────────────────────

    record Creado(long id, String email) {
    }

    interface Aviso {
    }

    record Caido(String servicio) implements Aviso {
    }

    static final class Avisos {

        final List<String> vistos = new ArrayList<>();

        @Listens
        public void alCrear(Creado evento) {
            vistos.add("creado:" + evento.id());
        }

        public void noEsOyente(Creado evento) {
            vistos.add("no debería");
        }
    }

    private static void eventosPorLambda() {
        Events bus = Events.bus();
        List<String> vistos = new ArrayList<>();
        bus.on(Creado.class, evento -> vistos.add("uno:" + evento.email()));
        bus.on(Creado.class, evento -> vistos.add("dos:" + evento.email()));

        bus.publish(new Creado(1, "a@b.c"));
        Check.equal("los dos oyentes reciben el evento", vistos.size(), 2);
        Check.equal("en el orden en que se registraron", vistos.get(0), "uno:a@b.c");
        Check.equal("cuenta los oyentes de un tipo", bus.listenerCount(Creado.class), 2);

        bus.off(Creado.class);
        bus.publish(new Creado(2, "x@y.z"));
        Check.equal("off los quita", vistos.size(), 2);

        Check.raises("no se publica null", IllegalArgumentException.class, () -> bus.publish(null));
    }

    private static void eventosPorAnotacion() {
        Events bus = Events.bus();
        Avisos servicio = new Avisos();
        bus.listeners(servicio);

        bus.publish(new Creado(7, "a@b.c"));
        Check.equal("el método anotado recibe el evento", servicio.vistos, List.of("creado:7"));
        Check.equal("el método sin anotar no", bus.listenerCount(Creado.class), 1);

        bus.listeners(servicio);
        Check.equal("registrar dos veces no duplica", bus.listenerCount(Creado.class), 1);

        Registry registro = new Registry();
        registro.add(new Avisos());
        Events desdeRegistro = Events.bus().listenersFrom(registro);
        Check.equal("también se cogen del registro de servicios",
                desdeRegistro.listenerCount(Creado.class), 1);
    }

    private static void eventosPorSupertipo() {
        Events bus = Events.bus();
        List<String> porInterfaz = new ArrayList<>();
        List<String> porTipo = new ArrayList<>();
        bus.on(Aviso.class, evento -> porInterfaz.add("interfaz"));
        bus.on(Caido.class, evento -> porTipo.add("exacto:" + evento.servicio()));

        bus.publish(new Caido("pagos"));
        Check.equal("quien escucha la interfaz también recibe", porInterfaz.size(), 1);
        Check.equal("y quien escucha el tipo exacto", porTipo, List.of("exacto:pagos"));

        bus.publish(new Creado(1, "a@b.c"));
        Check.equal("pero no le llega lo que no es suyo", porInterfaz.size(), 1);
    }

    private static void eventoQueFalla() {
        Events bus = Events.bus();
        List<String> vistos = new ArrayList<>();
        bus.on(Creado.class, evento -> {
            throw new IllegalStateException("oyente roto");
        });
        bus.on(Creado.class, evento -> vistos.add("el segundo sí corre"));

        bus.publish(new Creado(1, "a@b.c"));
        Check.equal("un oyente que revienta no impide los demás", vistos.size(), 1);
    }

    // ── perfiles ─────────────────────────────────────────────────────────────

    private static void perfiles() {
        Profiles vacio = Profiles.from(Config.empty());
        Check.equal("sin nada configurado el perfil es default", vacio.active(), "default");
        Check.that("y is(default) es cierto", vacio.is("default"));
        Check.that("no es dev", !vacio.dev());

        Profiles desdeConfig = Profiles.from(Config.empty().set("corvo.profiles", "Prod, metricas"));
        Check.equal("coge el primero como activo", desdeConfig.active(), "prod");
        Check.that("normaliza mayúsculas y espacios", desdeConfig.is("METRICAS"));
        Check.that("reconoce producción", desdeConfig.prod());
        Check.equal("y los lista todos", desdeConfig.all().size(), 2);

        Profiles dev = Profiles.of("development");
        Check.that("development también cuenta como dev", dev.dev());

        List<String> hecho = new ArrayList<>();
        Check.that("onlyIn ejecuta si el perfil está", dev.onlyIn("development", () -> hecho.add("sí")));
        Check.that("y no ejecuta si no está", !dev.onlyIn("prod", () -> hecho.add("no")));
        Check.equal("solo corrió una vez", hecho, List.of("sí"));

        System.setProperty("corvo.profiles", "pisado");
        try {
            Check.equal("la propiedad del sistema manda sobre la configuración",
                    Profiles.from(Config.empty().set("corvo.profiles", "delArchivo")).active(), "pisado");
        } finally {
            System.clearProperty("corvo.profiles");
        }
    }
}
