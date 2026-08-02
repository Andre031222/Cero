/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Contexto disponible en cada {@link JxFilter}.
 */
public final class JxFilterContext {

    private final JxRequest  request;
    private final JxResponse response;
    private final String     controller;
    private final String     action;

    JxFilterContext(JxRequest request, JxResponse response, String controller, String action) {
        this.request    = request;
        this.response   = response;
        this.controller = controller;
        this.action     = action;
    }

    // ── Request ───────────────────────────────────────────────────────────

    public JxRequest  request()           { return request; }
    public JxResponse response()          { return response; }
    public String     method()            { return request.method(); }
    public String     path()              { return request.path(); }
    public String     ip()                { return request.ip(); }
    public String     header(String name) { return request.header(name); }
    public String     param(String name)  { return request.param(name); }
    public boolean    isGet()             { return request.isGet(); }
    public boolean    isPost()            { return request.isPost(); }

    // ── Routing ───────────────────────────────────────────────────────────

    public String controller() { return controller; }
    public String action()     { return action; }

    // ── Sesión ────────────────────────────────────────────────────────────

    public Object session(String key) {
        var s = request.request.getSession(false);
        return s != null ? s.getAttribute(key) : null;
    }

    // ── Acceso directo al Servlet API ─────────────────────────────────────

    public HttpServletRequest  rawRequest()  { return request.raw(); }
    public HttpServletResponse rawResponse() { return response.raw(); }
}
