import corvo.core.Corvo;
import corvo.core.Result;

public class App {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Corvo.app()
           .port(port)
           .quiet()
           .routes(r -> r
               .get("/plaintext", ctx -> Result.text("OK"))
               .get("/json", ctx -> Result.raw("{\"message\":\"hello\",\"n\":42}"))
               .get("/db", ctx -> Result.raw(Db.json())))
           .start()
           .await();
    }
}
