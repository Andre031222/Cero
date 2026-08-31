package corvo.web;

import corvo.core.Context;
import corvo.core.Get;
import corvo.core.Path;
import corvo.core.Post;
import corvo.core.Query;
import corvo.core.Result;
import corvo.core.Route;

import java.util.LinkedHashMap;
import java.util.Map;

/** Las demostraciones que enseñan de qué es capaz el framework: CORS, sesión y errores. */
@Route("/demo")
public class DemoController {

    @Get("")
    public Result index() {
        return Result.html("Demo de Corvo — <b>funcionando</b>");
    }

    @Get("/saludo")
    public Result saludo(@Query("nombre") String nombre) {
        return Result.text("Hola " + (nombre == null || nombre.isBlank() ? "mundo" : nombre));
    }

    @Get("/ping")
    public Result ping() {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("pong", true);
        cuerpo.put("version", GeneradorProyecto.VERSION);
        return Result.json(cuerpo);
    }

    @Get("/cors-publico")
    public Result corsPublico() {
        return Result.raw("{\"ambito\":\"publico\",\"ok\":true}")
                .header("Access-Control-Allow-Origin", "*");
    }

    @Get("/crear")
    public Result crear(Context contexto) {
        contexto.session().set("t1", "valor creado");
        return Result.text("creado");
    }

    @Get("/ver")
    public Result ver(Context contexto) {
        var sesion = contexto.session(false);
        Object valor = sesion == null ? null : sesion.get("t1");
        return Result.text(valor != null ? "t1 = " + valor : "nulo");
    }

    @Get("/eco/{texto}")
    public Result eco(@Path("texto") String texto) {
        return Result.text("eco: " + texto);
    }

    @Post("/solo-post")
    public Result soloPost() {
        return Result.text("solo POST OK");
    }
}
