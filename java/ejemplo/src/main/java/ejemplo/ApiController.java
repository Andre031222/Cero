package ejemplo;

import lux.core.Body;
import lux.core.CsrfExempt;
import lux.core.Delete;
import lux.core.Get;
import lux.core.Inject;
import lux.core.Path;
import lux.core.Post;
import lux.core.Query;
import lux.core.Result;
import lux.core.Route;
import lux.core.Valid;
import lux.http.HttpException;

@Route("/api/tareas")
public class ApiController {

    @Inject
    Tareas tareas;

    @Get
    public Object index(@Query(value = "pagina", orElse = "1") int pagina) {
        return tareas.findPage(pagina, 20);
    }

    @Get("/{id}")
    public Object ver(@Path("id") long id) {
        Tarea encontrada = tareas.findById(id);
        if (encontrada == null) {
            throw new HttpException(404, "no existe la tarea " + id);
        }
        return encontrada;
    }

    @Post
    @CsrfExempt
    public Object crear(@Body @Valid Tarea tarea) {
        long id = tareas.insert(tarea);
        return Result.created(tareas.findById(id)).header("Location", "/api/tareas/" + id);
    }

    @Delete("/{id}")
    @CsrfExempt
    public Object borrar(@Path("id") long id) {
        return tareas.deleteById(id) == 0
                ? Result.status(404, "no existe la tarea " + id)
                : Result.noContent();
    }
}
