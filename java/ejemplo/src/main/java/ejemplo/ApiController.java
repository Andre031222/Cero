package ejemplo;

import corvo.core.Body;
import corvo.core.CsrfExempt;
import corvo.core.Delete;
import corvo.core.Get;
import corvo.core.Inject;
import corvo.core.Path;
import corvo.core.Post;
import corvo.core.Query;
import corvo.core.Result;
import corvo.core.Route;
import corvo.core.Valid;
import corvo.http.HttpException;

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
