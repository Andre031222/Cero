import io.javalin.Javalin;

public class App {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Javalin app = Javalin.create().start(port);
        app.get("/plaintext", ctx -> ctx.contentType("text/plain").result("OK"));
        app.get("/json", ctx -> ctx.contentType("application/json").result("{\"message\":\"hello\",\"n\":42}"));
        app.get("/db", ctx -> ctx.contentType("application/json").result(Db.json()));
    }
}
