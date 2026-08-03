package lux.web;

import lux.core.AccessLog;
import lux.core.Config;
import lux.core.Csrf;
import lux.core.Lux;
import lux.core.Metrics;
import lux.core.Profiles;
import lux.core.RateLimit;
import lux.core.SecurityHeaders;
import lux.http.Server;
import lux.http.ServerOptions;
import lux.http.StaticFiles;
import lux.view.Templates;

/** El sitio de LuxCore: portada, documentación, demostraciones, acceso y generador. */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : config.getInt("server.port", 8080);
        arrancar(puerto, config).await();
    }

    public static Server arrancar(int puerto, Config config) {
        Profiles perfiles = Profiles.from(config);
        Autenticacion auth = Autenticacion.desde(config);
        Metrics metricas = Metrics.enabled().ignore("/estaticos", "/panel-data");

        Templates plantillas = Templates.fromClasspath("plantillas")
                .suffix(".html")
                .reload(perfiles.dev());

        // Detrás de nginx: sin esto la aplicación se cree en texto plano y no marca la cookie
        // de sesión como Secure ni emite HSTS.
        boolean trasProxy = config.getBoolean("server.behindProxy", true);

        return Lux.app()
                .options(ServerOptions.builder().port(puerto).behindProxy(trasProxy).build())
                .config(config)
                .service(auth)
                .service(metricas)
                .service(perfiles)
                .views(plantillas)
                .fallback(StaticFiles.fromClasspath("estaticos", "/estaticos"))
                .authenticator(auth::identificar)
                .use(SecurityHeaders.standard()
                        .csp("default-src 'self'; img-src 'self' data: https:; "
                                + "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'"))
                .use(metricas)
                .use(AccessLog.combined())
                .use(RateLimit.perMinute(600))
                .use(Csrf.enabled().exempt("/demo/"))
                .controllers(InicioController.class, AuthController.class,
                        DemoController.class, GeneradorController.class,
                        InstalarController.class)
                .start();
    }
}
