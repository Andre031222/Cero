package cero.web;

import cero.core.Context;
import cero.core.Form;
import cero.core.Post;
import cero.core.Result;
import cero.core.Route;

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
                        "attachment; filename=\"" + peticion.artefacto() + "-cero.zip\"")
                .send(zip);
        return Result.noContent();
    }
}
