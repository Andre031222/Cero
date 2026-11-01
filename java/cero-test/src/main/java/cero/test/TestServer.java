package cero.test;

import cero.core.Cero;
import cero.http.Server;

import java.util.ArrayList;
import java.util.List;

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
