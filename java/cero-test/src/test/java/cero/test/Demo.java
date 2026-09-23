package cero.test;

import cero.core.Cero;
import cero.core.Result;
import cero.http.HttpMethod;
import cero.http.Session;

import java.util.List;
import java.util.Map;

/** La aplicación mínima contra la que se prueba el módulo. */
final class Demo {

    private Demo() {
    }

    static Cero app() {
        return Cero.app().routes(router -> {
            router.get("/hola", context -> Result.text("hola, mundo"));

            router.get("/json", context -> Result.json(Map.of(
                    "usuario", Map.of("nombre", "Ada", "edad", 36),
                    "items", List.of(Map.of("id", 7)))));

            router.post("/eco", context -> Result.raw(context.bodyText()));

            router.post("/formulario", context -> Result.text("hola, " + context.form("nombre")));

            router.on(HttpMethod.PATCH, "/parche", context -> Result.json(context.body(Map.class)));

            router.get("/cabecera", context -> Result.text(context.header("X-Token")));

            router.post("/acceso", context -> {
                Session session = context.session();
                session.set("usuario", context.form("usuario"));
                return Result.text("dentro");
            });

            router.get("/yo", context -> {
                Session session = context.session(false);
                Object usuario = session == null ? null : session.get("usuario");
                return usuario == null
                        ? Result.status(401, "anónimo")
                        : Result.json(Map.of("usuario", usuario));
            });
        });
    }
}
