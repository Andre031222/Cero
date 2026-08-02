package ejemplo;

import lux.core.Context;
import lux.core.Csrf;
import lux.core.Form;
import lux.core.Get;
import lux.core.Inject;
import lux.core.Path;
import lux.core.Post;
import lux.core.Result;
import lux.core.Route;
import lux.core.Validation;

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
