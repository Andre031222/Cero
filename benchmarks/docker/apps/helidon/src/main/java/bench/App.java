package bench;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.webserver.WebServer;

public class App {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        WebServer.builder()
            .port(port)
            .routing(r -> r
                .get("/plaintext", (req, res) -> {
                    res.headers().contentType(MediaTypes.TEXT_PLAIN);
                    res.send("OK");
                })
                .get("/json", (req, res) -> {
                    res.headers().contentType(MediaTypes.APPLICATION_JSON);
                    res.send("{\"message\":\"hello\",\"n\":42}");
                })
                .get("/db", (req, res) -> {
                    res.headers().contentType(MediaTypes.APPLICATION_JSON);
                    res.send(Db.json());
                }))
            .build()
            .start();
    }
}
