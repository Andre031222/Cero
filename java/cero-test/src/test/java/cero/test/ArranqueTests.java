package cero.test;

import java.net.http.HttpClient;
import java.util.Map;

final class ArranqueTests {

    private ArranqueTests() {
    }

    static void run() {
        Check.group("arranque y cliente");

        try (TestServer app = TestServer.start(Demo.app())) {
            Check.that("el puerto lo elige el sistema", app.port() > 0 && app.port() != 8080);
            Check.equal("url()", app.url(), "http://127.0.0.1:" + app.port());
            Check.equal("url(ruta)", app.url("hola"), "http://127.0.0.1:" + app.port() + "/hola");

            TestClient client = app.client();

            client.get("/hola").ok().contentType("text/plain").contains("mundo");
            Check.that("GET con aserciones encadenadas", true);

            client.get("/json")
                    .ok()
                    .contentType("application/json")
                    .json("usuario.nombre", "Ada")
                    .json("usuario.edad", 36)
                    .json("items.0.id", 7);
            Check.that("navegación por ruta json, con índice de lista", true);

            Check.equal("json() devuelve el valor", client.get("/json").json("usuario.nombre"), "Ada");
            Check.that("ruta json inexistente da null", client.get("/json").json("usuario.apodo") == null);

            client.post("/eco", Map.of("n", 1)).ok().json("n", 1);
            Check.that("POST serializa el objeto a json", true);

            client.post("/eco", "{\"crudo\":true}").ok().json("crudo", true);
            Check.that("POST con cadena la manda como json crudo", true);

            client.patch("/parche", Map.of("estado", "listo")).ok().json("estado", "listo");
            Check.that("PATCH", true);

            client.form("/formulario", Map.of("nombre", "Ada")).ok().contains("hola, Ada");
            Check.that("cuerpo de formulario", true);

            app.client().header("X-Token", "s3creto").get("/cabecera").ok().contains("s3creto");
            Check.that("cabecera fijada para todas las peticiones", true);

            Check.equal("404 de una ruta que no existe", client.get("/no-existe").status(404).status(), 404);
        }

        Check.group("versión del protocolo");

        try (TestServer app = TestServer.start(Demo.app())) {
            Check.equal("http1() fuerza HTTP/1.1",
                    app.client().http1().get("/hola").ok().version(), HttpClient.Version.HTTP_1_1);
            Check.equal("http2() fuerza HTTP/2",
                    app.client().http2().get("/hola").ok().version(), HttpClient.Version.HTTP_2);
        }

        Check.group("mensajes de fallo");

        try (TestServer app = TestServer.start(Demo.app())) {
            TestClient client = app.client();

            Check.fails("el estado dice esperado, obtenido y cuerpo",
                    () -> client.get("/hola").status(404), "404", "200", "hola, mundo");
            Check.fails("contains dice qué buscaba",
                    () -> client.get("/hola").contains("adiós"), "adiós", "hola, mundo");
            Check.fails("json dice la ruta y los dos valores",
                    () -> client.get("/json").json("usuario.nombre", "Grace"), "usuario.nombre",
                    "Grace", "Ada");
            Check.fails("contentType compara sin los parámetros",
                    () -> client.get("/hola").contentType("application/json"), "application/json",
                    "text/plain");
            Check.fails("cookie ausente",
                    () -> client.get("/hola").cookie("CEROSESSION"), "CEROSESSION");
        }

        Check.group("apagado");

        TestServer parado = TestServer.start(Demo.app());
        int puerto = parado.port();
        parado.close();
        Check.that("tras cerrar, el puerto ya no responde", noResponde(puerto));
    }

    private static boolean noResponde(int puerto) {
        try (TestClient client = TestClient.of("http://127.0.0.1:" + puerto)) {
            client.get("/hola");
            return false;
        } catch (RuntimeException esperado) {
            return true;
        }
    }
}
