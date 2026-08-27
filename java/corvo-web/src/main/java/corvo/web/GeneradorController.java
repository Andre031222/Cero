package corvo.web;

import corvo.core.Context;
import corvo.core.Form;
import corvo.core.Post;
import corvo.core.Result;
import corvo.core.Route;

@Route("/generar")
public class GeneradorController {

    @Post("/descargar")
    public Object descargar(Context contexto,
                            @Form("groupId") String grupo,
                            @Form("artifactId") String artefacto,
                            @Form("appName") String nombre,
                            @Form("db") String motor) throws Exception {

        GeneradorProyecto.Peticion peticion =
                GeneradorProyecto.Peticion.de(grupo, artefacto, nombre, motor);
        byte[] zip = GeneradorProyecto.construir(peticion);

        contexto.response()
                .header("Content-Type", "application/zip")
                .header("Content-Disposition",
                        "attachment; filename=\"" + peticion.artefacto() + "-luxcore.zip\"")
                .send(zip);
        return Result.noContent();
    }
}
