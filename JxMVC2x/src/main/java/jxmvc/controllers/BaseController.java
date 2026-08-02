package jxmvc.controllers;

import jxmvc.core.ActionResult;
import jxmvc.core.JxController;
import jxmvc.core.JxDB;
import jxmvc.core.JxException;
import jxmvc.core.JxJson;
import jxmvc.core.JxProfile;

/**
 * Controlador base de la aplicación.
 * Extender en lugar de JxController para tener acceso a helpers comunes.
 */
public abstract class BaseController extends JxController {

    /** Conexión a la BD por defecto (application.properties). */
    protected JxDB db() { return new JxDB(); }

    /**
     * Bloquea la acción fuera del perfil {@code dev}: responde 404 en producción.
     * Usar en endpoints de demostración que no deben quedar expuestos en un sitio público.
     */
    protected void requireDev() {
        if (!JxProfile.isDev())
            throw JxException.notFound("Ruta no encontrada");
    }

    /** Respuesta JSON con estado HTTP personalizado. */
    protected ActionResult jsonStatus(int status, Object body) {
        view.status(status);
        return json(body);
    }

    /** Respuesta JSON de éxito estándar: {@code {"ok":true,"data":...}} */
    protected ActionResult jsonOk(Object data) {
        return json("{\"ok\":true,\"data\":" + JxJson.toJson(data) + "}");
    }

    /** Respuesta JSON de error estándar: {@code {"ok":false,"error":"..."}} */
    protected ActionResult jsonError(int status, String message) {
        view.status(status);
        return json("{\"ok\":false,\"error\":" + JxJson.toJson(message) + "}");
    }

    /** Verifica que el param no sea nulo/vacío, lanza 400 si falla. */
    protected String requireParam(String name) {
        String v = model.param(name);
        if (v == null || v.isBlank())
            throw jxmvc.core.JxException.badRequest("Parámetro requerido: " + name);
        return v;
    }

    /** Verifica que el argumento de ruta exista, lanza 400 si falla. */
    protected String requireArg(int index) {
        String v = model.arg(index);
        if (v == null || v.isBlank())
            throw jxmvc.core.JxException.badRequest("Argumento de ruta requerido en posición " + index);
        return v;
    }
}
