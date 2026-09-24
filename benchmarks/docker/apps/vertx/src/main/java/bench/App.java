package bench;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

public class App {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Vertx vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.get("/plaintext").handler(ctx ->
            ctx.response().putHeader("content-type", "text/plain").end("OK"));
        router.get("/json").handler(ctx ->
            ctx.response().putHeader("content-type", "application/json").end("{\"message\":\"hello\",\"n\":42}"));
        // JDBC es bloqueante: se sirve en el pool de workers, no en el event loop.
        router.get("/db").blockingHandler(ctx ->
            ctx.response().putHeader("content-type", "application/json").end(Db.json()));
        vertx.createHttpServer().requestHandler(router).listen(port);
    }
}
