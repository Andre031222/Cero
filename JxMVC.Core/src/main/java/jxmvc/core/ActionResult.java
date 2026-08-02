/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resultado de una acción del controlador.
 * Soporta tipo, cuerpo, código HTTP personalizado y cabeceras adicionales.
 *
 * <pre>
 *   return view("users/list");
 *   return text("OK");
 *   return json(rowSet);
 *   return redirect("/home");
 *   return json(dto).status(201).header("X-Id", String.valueOf(id));
 *   return ActionResult.noContent();
 * </pre>
 */
public final class ActionResult {

    public enum Type { VIEW, TEXT, JSON, REDIRECT }

    private final Type   type;
    private final String payload;
    private int                  httpStatus = 0;           // 0 = usar el defecto del tipo
    private Map<String, String>  headers    = null;

    private ActionResult(Type type, String payload) {
        this.type    = type;
        this.payload = payload;
    }

    // ── Factories ─────────────────────────────────────────────────────────

    public static ActionResult view(String viewPath)   { return new ActionResult(Type.VIEW,     viewPath); }
    public static ActionResult text(String body)       { return new ActionResult(Type.TEXT,     body); }
    public static ActionResult json(String rawJson)    { return new ActionResult(Type.JSON,     rawJson); }
    public static ActionResult json(Object obj)        { return new ActionResult(Type.JSON,     JxJson.toJson(obj)); }
    public static ActionResult redirect(String target) { return new ActionResult(Type.REDIRECT, target); }

    /** 204 No Content — para DELETEs y operaciones sin cuerpo. */
    public static ActionResult noContent()             { return new ActionResult(Type.JSON, "").status(204); }

    /** 201 Created — conveniente para POST de creación. */
    public static ActionResult created(Object obj)     { return json(obj).status(201); }

    // ── Fluent API ────────────────────────────────────────────────────────

    /** Establece el código HTTP de respuesta. */
    public ActionResult status(int code) {
        this.httpStatus = code;
        return this;
    }

    /** Agrega una cabecera HTTP a la respuesta. */
    public ActionResult header(String name, String value) {
        if (headers == null) headers = new LinkedHashMap<>();
        headers.put(name, value);
        return this;
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public Type   type()       { return type; }
    public String payload()    { return payload; }
    public int    httpStatus() { return httpStatus; }

    public Map<String, String> headers() {
        return headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
    }

    public boolean hasCustomStatus()  { return httpStatus > 0; }
    public boolean hasCustomHeaders() { return headers != null && !headers.isEmpty(); }
}
