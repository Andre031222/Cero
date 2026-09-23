package cero.test;

import cero.core.Cero;
import cero.http.Server;

import java.util.ArrayList;
import java.util.List;

/**
 * Levanta una aplicación Cero en un puerto libre y la para al cerrarse.
 *
 * <pre>{@code
 * try (TestServer app = TestServer.start(Cero.app().controllers(Saludos.class))) {
 *     app.client().get("/hola").ok().contains("hola");
 * }
 * }</pre>
 *
 * <p>El puerto lo elige el sistema operativo, así que dos pruebas en paralelo no chocan. Al
 * cerrar se cierran también los clientes que entregó {@link #client()}: sin eso, sus conexiones
 * en keep-alive alargan el apagado del servidor y el final de la prueba deja de ser predecible.
 */
public final class TestServer implements AutoCloseable {

    private final Server server;
    private final List<TestClient> clients = new ArrayList<>();

    private TestServer(Server server) {
        this.server = server;
    }

    public static TestServer start(Cero app) {
        return new TestServer(app.host("127.0.0.1").port(0).quiet().start());
    }

    public static TestServer start(Class<?>... controllers) {
        return start(Cero.app().controllers(controllers));
    }

    public int port() {
        return server.port();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    public String url(String path) {
        return url() + (path.startsWith("/") ? path : "/" + path);
    }

    /** Un cliente nuevo, con su propio tarro de cookies: una sesión por cliente. */
    public TestClient client() {
        TestClient client = TestClient.of(url());
        clients.add(client);
        return client;
    }

    public Server server() {
        return server;
    }

    @Override
    public void close() {
        clients.forEach(TestClient::close);
        server.stop();
    }
}
