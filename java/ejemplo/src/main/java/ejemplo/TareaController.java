package ejemplo;

import corvo.core.Context;
import corvo.core.Csrf;
import corvo.core.Form;
import corvo.core.Get;
import corvo.core.Inject;
import corvo.core.Path;
import corvo.core.Post;
import corvo.core.Result;
import corvo.core.Route;
import corvo.core.Validation;

import java.util.LinkedHashMap;
import java.util.Map;

@Route("/")
public class TareaController {

    @Inject
    Tareas tareas;

    @Get
    public Object index(Context context) {
        return Result.view("lista.html", modelo(context, null));
    }

    @Post("/tareas")
    public Object crear(Context context,
                        @Form("titulo") String titulo,
                        @Form(value = "prioridad", orElse = "media") String prioridad) {
        Tarea nueva = Tarea.nueva(titulo == null ? "" : titulo.trim(), prioridad);
        Map<String, String> problemas = Validation.problems(nueva);

        if (!problemas.isEmpty()) {
            Map<String, Object> modelo = modelo(context, problemas.values().iterator().next());
            modelo.put("titulo", titulo);
            return Result.view("lista.html", modelo).status(422);
        }
        tareas.insert(nueva);
        return Result.redirect("/");
    }

    @Post("/tareas/{id}/completar")
    public Object completar(@Path("id") long id) {
        tareas.completar(id);
        return Result.redirect("/");
    }

    @Post("/tareas/{id}/borrar")
    public Object borrar(@Path("id") long id) {
        tareas.deleteById(id);
        return Result.redirect("/");
    }

    private Map<String, Object> modelo(Context context, String error) {
        Map<String, Object> modelo = new LinkedHashMap<>();
        modelo.put("tareas", tareas.findAll());
        modelo.put("pendientes", tareas.cuantasPendientes());
        modelo.put("csrf", Csrf.token(context));
        modelo.put("error", error);
        return modelo;
    }
}
