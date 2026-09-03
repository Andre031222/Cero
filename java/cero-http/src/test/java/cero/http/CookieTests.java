package cero.http;

import java.util.List;

final class CookieTests {

    private CookieTests() {
    }

    static void run() throws Exception {
        Check.group("cookies");

        encoding();
        validation();
        roundTrip();
    }

    private static void encoding() {
        Check.equal("cookie mínima",
                Cookie.of("a", "1").encode(), "a=1; Path=/; HttpOnly; SameSite=Lax");
        Check.equal("cookie completa",
                Cookie.of("s", "x").path("/app").domain("ejemplo.pe").maxAge(60)
                        .secure(true).httpOnly(false).sameSite("Strict").encode(),
                "s=x; Path=/app; Domain=ejemplo.pe; Max-Age=60; Secure; SameSite=Strict");
        Check.that("cookie expirada lleva Max-Age=0",
                Cookie.expired("s").encode().contains("Max-Age=0"));
        Check.that("sin maxAge no se emite Max-Age",
                !Cookie.of("a", "1").encode().contains("Max-Age"));
    }

    private static void validation() {
        Check.that("nombre con espacio se rechaza", rejects(() -> Cookie.of("a b", "1")));
        Check.that("nombre con punto y coma se rechaza", rejects(() -> Cookie.of("a;b", "1")));
        Check.that("valor con punto y coma se rechaza", rejects(() -> Cookie.of("a", "1;2")));
        Check.that("valor con CRLF se rechaza", rejects(() -> Cookie.of("a", "1\r\nX: y")));
        Check.that("valor con coma se rechaza", rejects(() -> Cookie.of("a", "1,2")));
        Check.that("nombre vacío se rechaza", rejects(() -> Cookie.of("", "1")));
    }

    private static void roundTrip() throws Exception {
        Handler handler = (req, res) -> {
            switch (req.path()) {
                case "/pon" -> {
                    res.cookie(Cookie.of("uno", "111"));
                    res.cookie(Cookie.of("dos", "222").maxAge(600));
                    res.text("puestas");
                }
                case "/lee" -> res.text(req.cookie("uno") + "|" + req.cookie("dos")
                        + "|" + req.cookies().size());
                default -> res.status(404).text("404");
            }
        };

        try (Server server = Server.start(ServerOptions.builder().port(0).build(), handler,
                ErrorReporter.silent())) {
            String base = "http://127.0.0.1:" + server.port();

            List<String> puestas = Fixture.get(base + "/pon").headers().allValues("set-cookie");
            Check.equal("se emiten dos Set-Cookie", puestas.size(), 2);
            Check.that("la primera es HttpOnly", puestas.get(0).contains("HttpOnly"));
            Check.that("la segunda lleva Max-Age", puestas.get(1).contains("Max-Age=600"));

            String leidas = Fixture.raw(server.port(),
                    "GET /lee HTTP/1.1\r\nHost: x\r\nCookie: uno=111; dos=222\r\nConnection: close\r\n\r\n");
            Check.that("las cookies del cliente se parsean", leidas.endsWith("111|222|2"));

            String repetida = Fixture.raw(server.port(),
                    "GET /lee HTTP/1.1\r\nHost: x\r\nCookie: uno=111\r\nCookie: dos=222\r\n"
                            + "Connection: close\r\n\r\n");
            Check.that("varias cabeceras Cookie se combinan", repetida.endsWith("111|222|2"));
        }
    }

    private static boolean rejects(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
