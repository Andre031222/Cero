package lux.http;

final class SessionTests {

    private SessionTests() {
    }

    static void run() throws Exception {
        Check.group("sesiones");

        overHttp();
        expiry();
    }

    private static void overHttp() throws Exception {
        Handler handler = (req, res) -> {
            switch (req.path()) {
                case "/entrar" -> {
                    Session session = req.session();
                    session.set("usuario", "andre");
                    res.text(session.id());
                }
                case "/quien" -> {
                    Session session = req.session(false);
                    res.text(session == null ? "anonimo" : String.valueOf(session.get("usuario")));
                }
                case "/salir" -> {
                    Session session = req.session(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    res.text("fuera");
                }
                default -> res.status(404).text("404");
            }
        };

        try (Server server = Server.start(ServerOptions.builder().port(0).build(), handler,
                ErrorReporter.silent())) {
            int port = server.port();

            String creada = Fixture.raw(port,
                    "GET /entrar HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            String cookie = Fixture.headerOf(creada, "Set-Cookie");
            Check.that("la primera petición emite la cookie de sesión",
                    cookie != null && cookie.startsWith(Sessions.COOKIE + "="));
            Check.that("la cookie de sesión es HttpOnly", cookie.contains("HttpOnly"));
            Check.that("la cookie de sesión es SameSite=Lax", cookie.contains("SameSite=Lax"));
            Check.that("sin TLS la cookie no es Secure", !cookie.contains("Secure"));

            String id = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
            Check.that("el id de sesión es largo", id.length() >= 40);

            Check.that("sin cookie no hay sesión",
                    Fixture.raw(port, "GET /quien HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                            .endsWith("anonimo"));

            String conCookie = Fixture.raw(port, "GET /quien HTTP/1.1\r\nHost: x\r\nCookie: "
                    + Sessions.COOKIE + "=" + id + "\r\nConnection: close\r\n\r\n");
            Check.that("con cookie se recupera el atributo", conCookie.endsWith("andre"));
            Check.that("no se reemite la cookie en peticiones siguientes",
                    Fixture.headerOf(conCookie, "Set-Cookie") == null);

            Fixture.raw(port, "GET /salir HTTP/1.1\r\nHost: x\r\nCookie: "
                    + Sessions.COOKIE + "=" + id + "\r\nConnection: close\r\n\r\n");
            Check.that("tras invalidar, la sesión ya no existe",
                    Fixture.raw(port, "GET /quien HTTP/1.1\r\nHost: x\r\nCookie: "
                            + Sessions.COOKIE + "=" + id + "\r\nConnection: close\r\n\r\n")
                            .endsWith("anonimo"));
        }
    }

    private static void expiry() throws Exception {
        Sessions store = new Sessions(150);
        Sessions.Entry entry = store.create();
        entry.set("k", "v");

        Check.equal("la sesión recién creada se encuentra", store.find(entry.id()), entry);
        Check.equal("la sesión guarda valores", entry.get("k"), "v");
        Check.equal("get tipado devuelve el valor", entry.get("k", String.class), "v");
        Check.equal("get tipado con tipo erróneo devuelve null", entry.get("k", Integer.class), null);

        Thread.sleep(300);
        Check.equal("la sesión caducada no se encuentra", store.find(entry.id()), null);

        Sessions.Entry otra = store.create();
        otra.invalidate();
        Check.that("la sesión invalidada deja de ser válida", !otra.valid());
        Check.equal("la sesión invalidada no se encuentra", store.find(otra.id()), null);
        Check.that("usar una sesión invalidada lanza error", rejects(() -> otra.set("a", "b")));

        Check.that("dos sesiones tienen ids distintos",
                !store.create().id().equals(store.create().id()));
    }

    private static boolean rejects(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }
}
