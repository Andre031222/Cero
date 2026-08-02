package lux.core;

import lux.http.HttpMethod;

final class RouterTests {

    private RouterTests() {
    }

    @Route("/usuarios")
    static final class UsuarioController {

        @Get
        public Object index() {
            return "lista";
        }

        @Get("/nuevo")
        public Object nuevo() {
            return "form";
        }

        @Get("/{id}")
        public Object ver() {
            return "uno";
        }

        @Get("/{id}/posts/{slug}")
        public Object post() {
            return "post";
        }

        @Post
        public Object crear() {
            return "creado";
        }

        @Delete("/{id}")
        public Object borrar() {
            return "borrado";
        }
    }

    static final class ReporteController {

        @Get("/mensual")
        public Object mensual() {
            return "mensual";
        }
    }

    static void run() {
        Check.group("router");

        patrones();
        registro();
        prioridad();
        convencion();
        errores();
    }

    private static void patrones() {
        RoutePattern raiz = RoutePattern.of("/");
        Check.that("la raíz coincide consigo misma", raiz.match("/") != null);
        Check.that("la raíz no coincide con /a", raiz.match("/a") == null);

        RoutePattern simple = RoutePattern.of("/usuarios/{id}");
        Check.equal("extrae la variable", simple.match("/usuarios/7").get("id"), "7");
        Check.that("no coincide con más segmentos", simple.match("/usuarios/7/x") == null);
        Check.that("no coincide con menos segmentos", simple.match("/usuarios") == null);
        Check.equal("normaliza la barra final", simple.match("/usuarios/7/").get("id"), "7");

        RoutePattern doble = RoutePattern.of("/a/{x}/b/{y}");
        Check.equal("primera variable", doble.match("/a/1/b/2").get("x"), "1");
        Check.equal("segunda variable", doble.match("/a/1/b/2").get("y"), "2");

        RoutePattern comodin = RoutePattern.of("/estaticos/*");
        Check.equal("el comodín captura el resto",
                comodin.match("/estaticos/css/a.css").get("*"), "css/a.css");
        Check.equal("el comodín acepta un solo segmento",
                comodin.match("/estaticos/a.css").get("*"), "a.css");

        Check.raises("comodín en medio se rechaza", IllegalArgumentException.class,
                () -> RoutePattern.of("/a/*/b"));
        Check.raises("variable mal formada se rechaza", IllegalArgumentException.class,
                () -> RoutePattern.of("/a/{id"));
    }

    private static void registro() {
        Router router = new Router().register(UsuarioController.class);
        Check.equal("registra todas las acciones", router.size(), 6);

        Router.Match lista = router.resolve(HttpMethod.GET, "/usuarios");
        Check.that("resuelve el índice", lista != null && !lista.methodNotAllowed());
        Check.equal("apunta a la acción correcta", lista.route().action().getName(), "index");

        Router.Match uno = router.resolve(HttpMethod.GET, "/usuarios/42");
        Check.equal("resuelve la variable", uno.pathVariables().get("id"), "42");
        Check.equal("y la acción", uno.route().action().getName(), "ver");

        Router.Match anidada = router.resolve(HttpMethod.GET, "/usuarios/42/posts/hola");
        Check.equal("ruta anidada: id", anidada.pathVariables().get("id"), "42");
        Check.equal("ruta anidada: slug", anidada.pathVariables().get("slug"), "hola");
    }

    private static void prioridad() {
        Router router = new Router().register(UsuarioController.class);
        Check.equal("la ruta literal gana a la variable",
                router.resolve(HttpMethod.GET, "/usuarios/nuevo").route().action().getName(), "nuevo");
        Check.equal("la variable atiende el resto",
                router.resolve(HttpMethod.GET, "/usuarios/otro").route().action().getName(), "ver");
    }

    private static void convencion() {
        Router router = new Router().register(ReporteController.class);
        Check.that("deriva la base del nombre de la clase",
                router.resolve(HttpMethod.GET, "/reporte/mensual") != null);
        Check.that("un valor vacío mapea a la ruta base",
                new Router().register(UsuarioController.class)
                        .resolve(HttpMethod.POST, "/usuarios") != null);

        Router lambdas = new Router()
                .get("/ping", ctx -> "pong")
                .post("/eco", ctx -> ctx.bodyText());
        Check.equal("registra rutas lambda", lambdas.size(), 2);
        Check.that("la ruta lambda resuelve",
                lambdas.resolve(HttpMethod.GET, "/ping").route().isLambda());
    }

    private static void errores() {
        Router router = new Router().register(UsuarioController.class);

        Check.equal("ruta inexistente devuelve null",
                router.resolve(HttpMethod.GET, "/nada"), null);

        Router.Match noPermitido = router.resolve(HttpMethod.PUT, "/usuarios/1");
        Check.that("verbo no permitido se distingue de 404",
                noPermitido != null && noPermitido.methodNotAllowed());
        Check.that("allowedFor lista los verbos",
                router.allowedFor("/usuarios/1").containsAll(
                        java.util.Set.of(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD)));

        Check.that("HEAD se resuelve contra GET",
                !router.resolve(HttpMethod.HEAD, "/usuarios").methodNotAllowed());

        Check.raises("ruta duplicada se rechaza", IllegalStateException.class,
                () -> new Router().get("/a", ctx -> "1").get("/a", ctx -> "2"));
    }
}
