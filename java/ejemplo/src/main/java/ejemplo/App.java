package ejemplo;

import corvo.core.Cors;
import corvo.core.Csrf;
import corvo.core.Corvo;
import corvo.core.RateLimit;
import corvo.core.SecurityHeaders;
import corvo.data.DataSources;
import corvo.data.Pool;
import corvo.http.Server;
import corvo.http.StaticFiles;
import corvo.view.Templates;

public final class App {

    private App() {
    }

    public static void main(String[] args) throws Exception {
        Server server = start(puerto(args), "jdbc:h2:mem:tareas;DB_CLOSE_DELAY=-1");
        server.await();
    }

    public static Server start(int puerto, String jdbc) {
        DataSources.registerDefault(Pool.to(jdbc).maxSize(8).build());
        Tareas.crearEsquema();

        return Corvo.app()
                .port(puerto)
                .views(Templates.fromClasspath("plantillas"))
                .fallback(StaticFiles.fromClasspath("estaticos", "/estaticos"))
                .use(SecurityHeaders.standard().csp("default-src 'self'"))
                .use(Cors.allowing("https://tareas.local"))
                .use(RateLimit.perMinute(300))
                .use(Csrf.enabled().exempt("/api/"))
                .controllers(TareaController.class, ApiController.class)
                .routes(rutas -> rutas.get("/salud", contexto -> "ok"))
                .start();
    }

    private static int puerto(String[] args) {
        return args.length > 0 ? Integer.parseInt(args[0]) : 8080;
    }
}
