package ejemplo;

import cero.core.Cors;
import cero.core.Csrf;
import cero.core.Cero;
import cero.core.RateLimit;
import cero.core.SecurityHeaders;
import cero.data.DataSources;
import cero.data.Pool;
import cero.http.Server;
import cero.http.StaticFiles;
import cero.view.Templates;

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

        return Cero.app()
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
