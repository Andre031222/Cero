package bench;

import io.jooby.Jooby;
import io.jooby.MediaType;
import io.jooby.ServerOptions;
import io.jooby.netty.NettyServer;

public class App extends Jooby {
    {
        get("/plaintext", ctx -> ctx.setResponseType(MediaType.text).send("OK"));
        get("/json", ctx -> ctx.setResponseType(MediaType.json).send("{\"message\":\"hello\",\"n\":42}"));
        get("/db", ctx -> ctx.setResponseType(MediaType.json).send(Db.json()));
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        runApp(args, new NettyServer(new ServerOptions().setPort(port)), App::new);
    }
}
