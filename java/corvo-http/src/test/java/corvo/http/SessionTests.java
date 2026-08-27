package corvo.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class SessionTests {

    private SessionTests() {
    }

    static void run() throws Exception {
        Check.group("sesiones");

        overHttp();
        expiry();
        rotacion();
        vidaMaxima();
        sinPisarse();
    }

    /**
     * A1 · Fijación de sesión. El identificador de antes de identificarse no puede seguir siendo
     * válido después: quien lo hubiera fijado en el navegador de la víctima se quedaría con una
     * sesión autenticada.
     */
    private static void rotacion() throws Exception {
        Sessions store = new Sessions(60_000);
        Sessions.Entry entry = store.create();
        entry.set("csrf", "abc123");
        String antes = entry.id();

        entry.regenerateId();
        String despues = entry.id();

        Check.that("rotar cambia el identificador", !antes.equals(despues));
        Check.equal("el identificador viejo deja de valer", store.find(antes), null);
        Check.equal("el nuevo encuentra la sesión", store.find(despues).id(), despues);
        Check.equal("y conserva el contenido, incluido el token CSRF",
                store.find(despues).get("csrf"), "abc123");
        Check.that("hay que reemitir la cookie", entry.idRotado());

        entry.cookieEmitida();
        Check.that("y una vez emitida, no se repite", !entry.idRotado());

        Sessions.Entry muerta = store.create();
        muerta.invalidate();
        boolean protesto = false;
        try {
            muerta.regenerateId();
        } catch (IllegalStateException esperado) {
            protesto = true;
        }
        Check.that("una sesión invalidada no se puede rotar", protesto);
    }

    /** B3 · Sin tope de vida, una sesión que se toque de vez en cuando no caduca nunca. */
    private static void vidaMaxima() throws Exception {
        Sessions conTope = new Sessions(60_000, SessionStore.inMemory(), 200);
        Sessions.Entry entry = conTope.create();

        Check.equal("dentro del tope se encuentra", conTope.find(entry.id()).id(), entry.id());

        // Se toca a mitad: con solo caducidad por inactividad, esto la mantendría viva siempre.
        Thread.sleep(120);
        conTope.find(entry.id());
        Thread.sleep(120);

        Check.equal("pasada la vida máxima caduca aunque se esté usando",
                conTope.find(entry.id()), null);
    }

    /**
     * B2 · Dos peticiones sobre la misma sesión. Cada una trabajaba con su copia y volcaba el
     * mapa entero, así que la última en guardar borraba lo que escribió la otra — en silencio.
     */
    private static void sinPisarse() throws Exception {
        Sessions store = new Sessions(60_000);
        Sessions.Entry base = store.create();
        String id = base.id();

        // Dos peticiones distintas sobre la misma sesión: cada una carga su propia vista.
        Sessions.Entry peticionA = store.find(id);
        Sessions.Entry peticionB = store.find(id);

        peticionA.set("csrf", "token-de-A");
        peticionB.set("carrito", "3 artículos");

        Sessions.Entry despues = store.find(id);
        Check.equal("lo que escribió la primera sigue ahí", despues.get("csrf"), "token-de-A");
        Check.equal("y lo que escribió la segunda también", despues.get("carrito"), "3 artículos");

        peticionA.remove("csrf");
        Sessions.Entry alFinal = store.find(id);
        Check.equal("borrar una clave no arrastra las otras",
                alFinal.get("carrito"), "3 artículos");
        Check.equal("y la borrada se fue", alFinal.get("csrf"), null);
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

        Check.equal("la sesión recién creada se encuentra por su id",
                store.find(entry.id()).id(), entry.id());
        Check.equal("y rehidratada trae sus valores", store.find(entry.id()).get("k"), "v");
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

    /**
     * Lo que hace falta para escalar: dos servidores distintos, un almacén compartido, y la
     * sesión abierta en uno vale en el otro. Con el almacén por proceso, la segunda instancia
     * no sabría nada de esa cookie.
     */
    static void almacenCompartido() throws Exception {
        Check.group("sesiones compartidas entre instancias");

        SessionStore compartido = SessionStore.inMemory();
        Handler handler = (peticion, respuesta) -> {
            Session sesion = peticion.session();
            if (peticion.path().equals("/guardar")) {
                sesion.set("quien", "ana");
                respuesta.text("guardado en " + respuesta.headers().hashCode());
            } else {
                Object quien = sesion.get("quien");
                respuesta.text(quien == null ? "nadie" : String.valueOf(quien));
            }
        };

        ServerOptions opciones = ServerOptions.builder().port(0).sessionStore(compartido).build();
        try (Server primera = Server.start(opciones, handler, ErrorReporter.silent());
             Server segunda = Server.start(opciones, handler, ErrorReporter.silent())) {

            HttpResponse<String> alta = Fixture.get("http://127.0.0.1:" + primera.port() + "/guardar");
            String cookie = alta.headers().firstValue("set-cookie").orElse("");
            int fin = cookie.indexOf(';');
            String galleta = fin < 0 ? cookie : cookie.substring(0, fin);
            Check.that("la primera instancia abre sesión", !galleta.isEmpty());

            HttpResponse<String> enLaOtra = Fixture.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + segunda.port() + "/leer"))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("Cookie", galleta).build());
            Check.equal("y la segunda instancia ve los mismos datos", enLaOtra.body(), "ana");
        }

        try (Server aislada = Server.start(ServerOptions.builder().port(0).build(),
                handler, ErrorReporter.silent())) {
            HttpResponse<String> alta = Fixture.get("http://127.0.0.1:" + aislada.port() + "/guardar");
            String cookie = alta.headers().firstValue("set-cookie").orElse("");
            int fin = cookie.indexOf(';');

            try (Server otra = Server.start(ServerOptions.builder().port(0).build(),
                    handler, ErrorReporter.silent())) {
                HttpResponse<String> perdida = Fixture.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + otra.port() + "/leer"))
                                .version(HttpClient.Version.HTTP_1_1)
                                .header("Cookie", fin < 0 ? cookie : cookie.substring(0, fin)).build());
                Check.equal("sin almacén compartido, la otra instancia no la reconoce",
                        perdida.body(), "nadie");
            }
        }
    }
}