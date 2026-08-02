package lux.core;

import lux.http.ErrorReporter;
import lux.http.Server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class GuardTests {

    private GuardTests() {
    }

    @Route("/api")
    static final class ApiController {

        @Get
        public Object index() {
            return "ok";
        }

        @Post("/guardar")
        public Object guardar() {
            return "guardado";
        }

        @Post("/webhook")
        @CsrfExempt
        public Object webhook() {
            return "recibido";
        }

        @Get("/token")
        public Object token(Context context) {
            return Csrf.token(context);
        }

        @Post("/registro")
        public Object registro(@Body @Valid ValidationTests.Minimo datos) {
            return datos.nombre();
        }
    }

    static void run() throws Exception {
        Check.group("CORS");
        cors();

        Check.group("CSRF");
        csrf();

        Check.group("rate limit");
        rateLimit();

        Check.group("sanitizado");
        sanitizado();

        Check.group("@Valid sobre el cuerpo");
        validacionEnRuta();
    }

    private static void cors() throws Exception {
        Server server = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .use(Cors.allowing("https://app.pe").credentials(true))
                .controllers(ApiController.class)
                .start();
        try {
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> permitido = send(base + "/api", "GET",
                    "Origin", "https://app.pe");
            Check.equal("origen permitido recibe la cabecera",
                    permitido.headers().firstValue("access-control-allow-origin").orElse(null),
                    "https://app.pe");
            Check.equal("con credenciales",
                    permitido.headers().firstValue("access-control-allow-credentials").orElse(null),
                    "true");
            Check.equal("y Vary: Origin",
                    permitido.headers().firstValue("vary").orElse(null), "Origin");
            Check.equal("la petición sigue su curso", permitido.body(), "ok");

            HttpResponse<String> ajeno = send(base + "/api", "GET", "Origin", "https://malo.pe");
            Check.that("un origen no permitido no recibe cabecera CORS",
                    ajeno.headers().firstValue("access-control-allow-origin").isEmpty());
            Check.equal("pero la petición simple no se bloquea", ajeno.statusCode(), 200);

            HttpResponse<String> sinOrigen = send(base + "/api", "GET");
            Check.that("sin Origin no se añade nada",
                    sinOrigen.headers().firstValue("access-control-allow-origin").isEmpty());

            HttpResponse<String> preflight = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Origin", "https://app.pe")
                            .header("Access-Control-Request-Method", "POST")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            Check.equal("el preflight responde 204", preflight.statusCode(), 204);
            Check.that("anuncia los métodos",
                    preflight.headers().firstValue("access-control-allow-methods").orElse("")
                            .contains("POST"));
            Check.that("anuncia las cabeceras",
                    preflight.headers().firstValue("access-control-allow-headers").orElse("")
                            .contains("Content-Type"));
            Check.that("y el tiempo de caché",
                    preflight.headers().firstValue("access-control-max-age").isPresent());

            HttpResponse<String> preflightAjeno = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Origin", "https://malo.pe")
                            .header("Access-Control-Request-Method", "POST")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            Check.equal("un preflight de origen ajeno da 403", preflightAjeno.statusCode(), 403);
        } finally {
            server.stop();
        }

        Server abierto = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .use(Cors.anyOrigin())
                .controllers(ApiController.class)
                .start();
        try {
            Check.equal("anyOrigin responde con comodín",
                    send("http://127.0.0.1:" + abierto.port() + "/api", "GET",
                            "Origin", "https://cualquiera.pe")
                            .headers().firstValue("access-control-allow-origin").orElse(null), "*");
        } finally {
            abierto.stop();
        }
    }

    private static void csrf() throws Exception {
        Server server = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .use(Csrf.enabled().exempt("/api/publico"))
                .controllers(ApiController.class)
                .start();
        try {
            String base = "http://127.0.0.1:" + server.port();

            Check.equal("GET pasa sin token", send(base + "/api", "GET").statusCode(), 200);

            HttpResponse<String> sinToken = send(base + "/api/guardar", "POST");
            Check.equal("POST sin token da 403", sinToken.statusCode(), 403);

            HttpResponse<String> emision = send(base + "/api/token", "GET");
            String cookie = emision.headers().firstValue("set-cookie").orElse("");
            String token = emision.body();
            Check.that("se emite un token", token.length() >= 40);
            Check.that("y una sesión", cookie.contains("LUXSESSION"));

            String sesion = cookie.substring(0, cookie.indexOf(';'));

            HttpResponse<String> conToken = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/guardar"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Cookie", sesion)
                            .header(Csrf.HEADER, token)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            Check.equal("POST con token válido pasa", conToken.statusCode(), 200);

            HttpResponse<String> tokenMalo = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/guardar"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Cookie", sesion)
                            .header(Csrf.HEADER, "token-inventado")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            Check.equal("POST con token erróneo da 403", tokenMalo.statusCode(), 403);

            HttpResponse<String> exento = send(base + "/api/webhook", "POST");
            Check.equal("@CsrfExempt salta la comprobación", exento.statusCode(), 200);
        } finally {
            server.stop();
        }
    }

    private static void rateLimit() throws Exception {
        RateLimit limite = RateLimit.of(3, Duration.ofSeconds(30));
        Server server = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .use(limite)
                .controllers(ApiController.class)
                .start();
        try {
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> primera = send(base + "/api", "GET");
            Check.equal("la primera pasa", primera.statusCode(), 200);
            Check.equal("anuncia el límite",
                    primera.headers().firstValue("x-ratelimit-limit").orElse(null), "3");
            Check.equal("y lo que queda",
                    primera.headers().firstValue("x-ratelimit-remaining").orElse(null), "2");

            send(base + "/api", "GET");
            send(base + "/api", "GET");

            HttpResponse<String> cuarta = send(base + "/api", "GET");
            Check.equal("la cuarta da 429", cuarta.statusCode(), 429);
            Check.that("con Retry-After", cuarta.headers().firstValue("retry-after").isPresent());
            Check.equal("y sin cupo restante",
                    cuarta.headers().firstValue("x-ratelimit-remaining").orElse(null), "0");

            Check.equal("otra ruta lleva su propia cuenta",
                    send(base + "/api/token", "GET").statusCode(), 200);

            limite.reset();
            Check.equal("reset devuelve el cupo", send(base + "/api", "GET").statusCode(), 200);
        } finally {
            server.stop();
        }
    }

    private static void sanitizado() {
        Check.equal("elimina script",
                Sanitize.html("hola <script>alert(1)</script> mundo"), "hola  mundo");
        Check.equal("elimina style",
                Sanitize.html("a<style>body{}</style>b"), "ab");
        Check.equal("elimina iframe",
                Sanitize.html("a<iframe src=x></iframe>b"), "ab");
        Check.equal("elimina manejadores de evento",
                Sanitize.html("<div onclick=\"malo()\">x</div>"), "<div>x</div>");
        Check.equal("elimina el protocolo javascript",
                Sanitize.html("<a href=\"javascript:malo()\">x</a>"), "<a href=\"malo()\">x</a>");
        Check.equal("conserva el marcado inocuo",
                Sanitize.html("<p><b>hola</b></p>"), "<p><b>hola</b></p>");

        Check.equal("text quita todas las etiquetas",
                Sanitize.text("<p>hola <b>mundo</b></p>"), "hola mundo");
        Check.equal("text sobre script no deja rastro",
                Sanitize.text("<script>alert(1)</script>texto"), "texto");

        Check.equal("filename quita la ruta", Sanitize.filename("../../etc/passwd"), "passwd");
        Check.equal("filename quita separadores de Windows",
                Sanitize.filename("C:\\temp\\a.txt"), "a.txt");
        Check.equal("filename limpia caracteres raros",
                Sanitize.filename("in forme;rm -rf.pdf"), "in_forme_rm_-rf.pdf");
        Check.equal("filename nunca queda vacío", Sanitize.filename("..."), "archivo");
        Check.equal("filename deja pasar lo normal", Sanitize.filename("informe_2026.pdf"),
                "informe_2026.pdf");
    }

    private static void validacionEnRuta() throws Exception {
        Server server = Lux.app().port(0).quiet().reporter(ErrorReporter.silent())
                .controllers(ApiController.class)
                .start();
        try {
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> bueno = post(base + "/api/registro", "{\"nombre\":\"Ana\"}");
            Check.equal("un cuerpo válido pasa", bueno.statusCode(), 200);
            Check.equal("y llega al controlador", bueno.body(), "Ana");

            HttpResponse<String> malo = post(base + "/api/registro", "{\"nombre\":\"\"}");
            Check.equal("un cuerpo inválido da 422", malo.statusCode(), 422);
            Check.that("y detalla el campo", malo.body().contains("\"nombre\":\"es obligatorio\""));
            Check.that("bajo la clave fields", malo.body().contains("\"fields\""));
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> send(String url, String method, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create(url))
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
