package lux.view;

import lux.core.Get;
import lux.core.Lux;
import lux.core.Path;
import lux.core.Result;
import lux.core.Route;
import lux.http.ErrorReporter;
import lux.http.Server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

final class IntegrationTests {

    private IntegrationTests() {
    }

    @Route("/paginas")
    public static final class PaginaController {

        @Get("/{nombre}")
        public Object mostrar(@Path("nombre") String nombre) {
            return Result.view("hija.html", Map.of(
                    "titulo", "Página de " + nombre,
                    "items", List.of("uno", "dos"),
                    "peligro", "<script>alert(1)</script>"));
        }

        @Get("/sin-motor")
        public Object sinMotor() {
            return Result.view("nada.html", Map.of());
        }
    }

    static void run() throws Exception {
        Check.group("integración con lux-core");

        java.nio.file.Path raiz = Files.createTempDirectory("lux-view-web");
        Files.writeString(raiz.resolve("base.html"),
                "<h1>{% block titulo %}—{% end %}</h1>{% block cuerpo %}{% end %}");
        Files.writeString(raiz.resolve("hija.html"),
                "{% extends \"base.html\" %}"
                        + "{% block titulo %}{{ titulo }}{% end %}"
                        + "{% block cuerpo %}<ul>{% for i in items %}<li>{{ i }}</li>{% end %}</ul>"
                        + "<p>{{ peligro }}</p>{% end %}");

        Server server = Lux.app()
                .port(0)
                .quiet()
                .reporter(ErrorReporter.silent())
                .views(Templates.from(raiz))
                .controllers(PaginaController.class)
                .start();

        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> pagina = get(base + "/paginas/inicio");

            Check.equal("la vista se renderiza", pagina.statusCode(), 200);
            Check.equal("se sirve como HTML",
                    pagina.headers().firstValue("content-type").orElse(null), "text/html; charset=utf-8");
            Check.that("el layout aplica", pagina.body().startsWith("<h1>Página de inicio</h1>"));
            Check.that("el bucle renderiza", pagina.body().contains("<li>uno</li><li>dos</li>"));
            Check.that("el modelo se escapa",
                    pagina.body().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
            Check.that("y no cuela el script crudo", !pagina.body().contains("<script>"));

            Check.equal("una vista inexistente da 500", get(base + "/paginas/sin-motor").statusCode(), 500);
        } finally {
            server.stop();
        }

        Server sinVistas = Lux.app().port(0).quiet()
                .reporter(ErrorReporter.silent())
                .controllers(PaginaController.class)
                .start();
        try {
            Check.equal("sin motor configurado, Result.view da 501",
                    get("http://127.0.0.1:" + sinVistas.port() + "/paginas/x").statusCode(), 501);
        } finally {
            sinVistas.stop();
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(HttpRequest.newBuilder(URI.create(url)).version(HttpClient.Version.HTTP_1_1).build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
