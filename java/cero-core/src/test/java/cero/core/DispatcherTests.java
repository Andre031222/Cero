package cero.core;

import cero.http.ErrorReporter;
import cero.http.HttpException;
import cero.http.Server;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

final class DispatcherTests {

    private DispatcherTests() {
    }

    /** Los 256 valores de un byte, con el 0 y el 0xFF dentro a propósito. */
    private static final byte[] TODOS_LOS_BYTES = todosLosBytes();

    private static byte[] todosLosBytes() {
        byte[] datos = new byte[256];
        for (int i = 0; i < 256; i++) {
            datos[i] = (byte) i;
        }
        return datos;
    }

    record Articulo(String titulo, int paginas) {
    }

    record Busqueda(String q, int pagina) {
    }

    @Service
    static final class Catalogo {
        Articulo porId(int id) {
            return new Articulo("artículo " + id, id * 10);
        }
    }

    @Route("/api")
    static final class ApiController {

        @Inject
        Catalogo catalogo;

        @Get
        public Object index() {
            return "raíz";
        }

        /** Los 256 bytes posibles: si algo se codifica por el camino, aquí se nota. */
        @Get("/binario")
        public Object binario() {
            return Result.bytes(TODOS_LOS_BYTES, "application/octet-stream");
        }

        @Get("/descarga")
        public Object descarga() {
            return Result.download(TODOS_LOS_BYTES, "informe final.bin", "application/octet-stream");
        }

        @Get("/descarga-con-trampa")
        public Object descargaConTrampa() {
            return Result.download(new byte[] {1, 2, 3},
                    "a\"b\r\nSet-Cookie: sesion=robada", "application/octet-stream");
        }

        @Get("/texto")
        public String texto() {
            return "en texto plano";
        }

        @Get("/objeto")
        public Object objeto() {
            return new Articulo("uno", 10);
        }

        @Get("/articulos/{id}")
        public Object porId(@Path("id") int id) {
            return catalogo.porId(id);
        }

        @Get("/buscar")
        public Object buscar(@Query("q") String termino, @Query(value = "pagina", orElse = "1") int pagina) {
            return new Busqueda(termino, pagina);
        }

        @Post("/articulos")
        public Object crear(@Body Articulo articulo) {
            return Result.created(articulo).header("X-Creado", articulo.titulo());
        }

        @Get("/cabecera")
        public Object cabecera(@Header("X-Prueba") String valor) {
            return valor == null ? "sin cabecera" : valor;
        }

        @Get("/galleta")
        public Object galleta(@CookieValue("sabor") String sabor) {
            return sabor == null ? "sin galleta" : sabor;
        }

        @Get("/vacio")
        public Object vacio() {
            return Result.noContent();
        }

        @Get("/nulo")
        public Object nulo() {
            return null;
        }

        @Get("/mudanza")
        public Object mudanza() {
            return Result.redirect("/api/texto");
        }

        @Get("/html")
        public Object html() {
            return Result.html("<h1>hola</h1>");
        }

        @Get("/contexto")
        public Object contexto(Context context) {
            return context.method() + " " + context.path();
        }

        @Get("/estalla")
        public Object estalla() {
            throw new IllegalStateException("algo se rompió");
        }

        @Get("/negocio")
        public Object negocio() {
            throw new ReglaRota("saldo insuficiente");
        }

        @Get("/no-esta")
        public Object noEsta() {
            throw new HttpException(404, "el artículo no existe");
        }

        @OnError(ReglaRota.class)
        public Object reglaRota(ReglaRota fallo) {
            return Result.json(java.util.Map.of("motivo", fallo.getMessage())).status(422);
        }
    }

    @Route("/privado")
    static final class PrivadoController {

        @Get("/perfil")
        @RequireAuth
        public Object perfil(Principal principal) {
            return principal.id();
        }

        @Get("/admin")
        @RequireRole("admin")
        public Object admin() {
            return "panel";
        }

        @Get("/abierto")
        public Object abierto() {
            return "libre";
        }
    }

    static final class ReglaRota extends RuntimeException {
        ReglaRota(String message) {
            super(message);
        }
    }

    static void run() throws Exception {
        Check.group("dispatcher");

        List<String> traza = new ArrayList<>();

        Server server = Cero.app()
                .port(0)
                .quiet()
                .reporter(ErrorReporter.silent())
                .controllers(ApiController.class, PrivadoController.class)
                .routes(router -> router.get("/ping", ctx -> "pong"))
                .authenticator(context -> {
                    String token = context.header("Authorization");
                    if (token == null) {
                        return null;
                    }
                    return token.equals("Bearer admin")
                            ? Principal.of("andre", "admin")
                            : Principal.of("invitado");
                })
                .use((context, chain) -> {
                    traza.add("entra");
                    Object outcome = chain.proceed(context);
                    traza.add("sale");
                    return outcome;
                })
                .use((context, chain) -> {
                    traza.add("dentro");
                    return chain.proceed(context);
                })
                .start();

        String base = "http://127.0.0.1:" + server.port();
        try {
            enrutado(base);
            binding(base);
            resultados(base);
            binarios(base);
            seguridad(base);
            errores(base);
            middleware(base, traza);
        } finally {
            server.stop();
        }
    }

    private static void enrutado(String base) throws Exception {
        Check.equal("índice del controlador", Cliente.get(base + "/api").body(), "raíz");
        Check.equal("ruta lambda", Cliente.get(base + "/ping").body(), "pong");
        Check.equal("ruta inexistente da 404", Cliente.get(base + "/nada").statusCode(), 404);

        HttpResponse<String> noPermitido = Cliente.method(base + "/api/texto", "DELETE");
        Check.equal("verbo no permitido da 405", noPermitido.statusCode(), 405);
        Check.that("405 incluye Allow",
                noPermitido.headers().firstValue("allow").orElse("").contains("GET"));

        Check.equal("HEAD funciona sobre GET",
                Cliente.method(base + "/api/texto", "HEAD").statusCode(), 200);
    }

    private static void binding(String base) throws Exception {
        Check.equal("variable de ruta convertida a int",
                Cliente.get(base + "/api/articulos/3").body(),
                "{\"titulo\":\"artículo 3\",\"paginas\":30}");
        Check.equal("variable de ruta inválida da 400",
                Cliente.get(base + "/api/articulos/abc").statusCode(), 400);

        Check.equal("parámetro de consulta",
                Cliente.get(base + "/api/buscar?q=cero&pagina=4").body(), "{\"q\":\"cero\",\"pagina\":4}");
        Check.equal("parámetro con valor por defecto",
                Cliente.get(base + "/api/buscar?q=cero").body(), "{\"q\":\"cero\",\"pagina\":1}");

        HttpResponse<String> creado = Cliente.post(base + "/api/articulos",
                "{\"titulo\":\"nuevo\",\"paginas\":7}");
        Check.equal("cuerpo JSON vinculado a record", creado.statusCode(), 201);
        Check.equal("y devuelto", creado.body(), "{\"titulo\":\"nuevo\",\"paginas\":7}");
        Check.equal("con cabecera propia",
                creado.headers().firstValue("x-creado").orElse(null), "nuevo");

        Check.equal("cabecera vinculada",
                Cliente.get(base + "/api/cabecera", "X-Prueba", "valor").body(), "valor");
        Check.equal("cabecera ausente queda null",
                Cliente.get(base + "/api/cabecera").body(), "sin cabecera");
        Check.equal("cookie vinculada",
                Cliente.get(base + "/api/galleta", "Cookie", "sabor=vainilla").body(), "vainilla");

        Check.equal("el contexto se inyecta",
                Cliente.get(base + "/api/contexto").body(), "GET /api/contexto");
        Check.equal("la dependencia del controlador se inyecta",
                Cliente.get(base + "/api/articulos/5").body(),
                "{\"titulo\":\"artículo 5\",\"paginas\":50}");
    }

    private static void resultados(String base) throws Exception {
        HttpResponse<String> texto = Cliente.get(base + "/api/texto");
        Check.equal("String se sirve como texto plano",
                texto.headers().firstValue("content-type").orElse(null), "text/plain; charset=utf-8");

        HttpResponse<String> objeto = Cliente.get(base + "/api/objeto");
        Check.equal("un objeto se serializa a JSON",
                objeto.headers().firstValue("content-type").orElse(null), "application/json");
        Check.equal("con el contenido correcto", objeto.body(), "{\"titulo\":\"uno\",\"paginas\":10}");

        Check.equal("noContent da 204", Cliente.get(base + "/api/vacio").statusCode(), 204);
        Check.equal("devolver null da 204", Cliente.get(base + "/api/nulo").statusCode(), 204);

        HttpResponse<String> redirigido = Cliente.get(base + "/api/mudanza");
        Check.equal("redirect da 302", redirigido.statusCode(), 302);
        Check.equal("con Location",
                redirigido.headers().firstValue("location").orElse(null), "/api/texto");

        Check.equal("html se sirve como html",
                Cliente.get(base + "/api/html").headers().firstValue("content-type").orElse(null),
                "text/html; charset=utf-8");
    }

    private static void seguridad(String base) throws Exception {
        Check.equal("ruta abierta no exige nada",
                Cliente.get(base + "/privado/abierto").statusCode(), 200);

        Check.equal("sin credenciales da 401",
                Cliente.get(base + "/privado/perfil").statusCode(), 401);
        Check.equal("con credenciales devuelve el principal",
                Cliente.get(base + "/privado/perfil", "Authorization", "Bearer admin").body(), "andre");

        Check.equal("rol insuficiente da 403",
                Cliente.get(base + "/privado/admin", "Authorization", "Bearer otro").statusCode(), 403);
        Check.equal("rol correcto pasa",
                Cliente.get(base + "/privado/admin", "Authorization", "Bearer admin").body(), "panel");
        Check.equal("sin autenticar el rol también da 401",
                Cliente.get(base + "/privado/admin").statusCode(), 401);
    }

    private static void errores(String base) throws Exception {
        HttpResponse<String> roto = Cliente.get(base + "/api/estalla");
        Check.equal("una excepción no controlada da 500", roto.statusCode(), 500);
        Check.that("el 500 no filtra el mensaje interno", !roto.body().contains("algo se rompió"));
        Check.that("el 500 responde JSON con la ruta", roto.body().contains("/api/estalla"));

        HttpResponse<String> negocio = Cliente.get(base + "/api/negocio");
        Check.equal("@OnError fija el estado", negocio.statusCode(), 422);
        Check.equal("@OnError construye el cuerpo", negocio.body(), "{\"motivo\":\"saldo insuficiente\"}");

        HttpResponse<String> ausente = Cliente.get(base + "/api/no-esta");
        Check.equal("HttpException conserva el estado", ausente.statusCode(), 404);
        Check.that("y el mensaje", ausente.body().contains("el artículo no existe"));
    }

    /**
     * El binario tiene que llegar byte a byte.
     *
     * <p>Se comprueba con los 256 valores posibles porque el modo de fallar es sutil: si el
     * cuerpo pasa por un String, los bytes sobre 0x7F se convierten en el carácter de sustitución
     * y el archivo llega con un tamaño parecido pero corrupto. Un «hola» en ASCII pasaría la
     * prueba sin enterarse de nada.
     */
    private static void binarios(String base) throws Exception {
        HttpResponse<byte[]> crudo = Cliente.bytes(base + "/api/binario");
        Check.equal("el binario responde 200", crudo.statusCode(), 200);
        Check.equal("y con su tipo",
                crudo.headers().firstValue("Content-Type").orElse(""), "application/octet-stream");
        Check.equal("llegan todos los bytes", crudo.body().length, 256);
        Check.that("y sin tocar ninguno", java.util.Arrays.equals(crudo.body(), TODOS_LOS_BYTES));

        HttpResponse<byte[]> descarga = Cliente.bytes(base + "/api/descarga");
        Check.equal("download nombra el archivo",
                descarga.headers().firstValue("Content-Disposition").orElse(""),
                "attachment; filename=\"informe final.bin\"");

        // Un nombre con comillas y un salto de línea podría cerrar la cabecera y colar otra.
        HttpResponse<byte[]> trampa = Cliente.bytes(base + "/api/descarga-con-trampa");
        String disposicion = trampa.headers().firstValue("Content-Disposition").orElse("");
        Check.equal("el nombre de archivo sale saneado",
                disposicion, "attachment; filename=\"a_b__Set-Cookie: sesion=robada\"");
        Check.that("y no aparece una cookie inyectada",
                trampa.headers().firstValue("Set-Cookie").isEmpty());
    }

    private static void middleware(String base, List<String> traza) throws Exception {
        traza.clear();
        Cliente.get(base + "/api/texto");
        Check.equal("el middleware envuelve la acción en orden",
                String.join(",", traza), "entra,dentro,sale");

        traza.clear();
        Cliente.get(base + "/nada");
        Check.equal("el middleware también corre en un 404",
                String.join(",", traza), "entra,dentro");
    }
}
