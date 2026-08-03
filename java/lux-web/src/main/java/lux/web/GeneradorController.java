package lux.web;

import lux.core.Context;
import lux.core.Form;
import lux.core.Post;
import lux.core.Result;
import lux.core.Route;

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
