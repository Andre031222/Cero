package ejemplo;

import lux.core.Cors;
import lux.core.Csrf;
import lux.core.Lux;
import lux.core.RateLimit;
import lux.data.DataSources;
import lux.data.Pool;
import lux.http.Server;
import lux.http.StaticFiles;
import lux.view.Templates;

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

        return Lux.app()
                .port(puerto)
                .views(Templates.fromClasspath("plantillas"))
                .fallback(StaticFiles.fromClasspath("estaticos", "/estaticos"))
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
