/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpSession;

/**
 * Clase base de todos los controladores JxMVC.
 *
 * <pre>
 *   &#64;JxControllerMapping("users")
 *   public class UserController extends JxController {
 *
 *       &#64;JxGetMapping("list")
 *       public ActionResult list() {
 *           model.setVar("users", db.getTable("users"));
 *           return view("users/list");
 *       }
 *
 *       &#64;JxPostMapping("save")
 *       public ActionResult save() {
 *           String name = post("name");
 *           return redirect("/users/list");
 *       }
 *
 *       &#64;JxGetMapping("api")
 *       public ActionResult api() {
 *           return json(db.getTable("users"));
 *       }
 *   }
 * </pre>
 */
public abstract class JxController {

    protected JxRequest  model;
    protected JxResponse view;

    final void bindContext(JxRequest model, JxResponse view) {
        this.model = model;
        this.view  = view;
    }

    // ── Parámetros ────────────────────────────────────────────────────────

    /** Parámetro GET/POST sanitizado. */
    protected String post(String name)  { return model.param(name); }

    /** Alias de post(). */
    protected String param(String name) { return model.param(name); }

    /** Argumento de ruta en posición {@code index} (sanitizado). */
    protected String arg(int index)     { return model.arg(index); }

    /** Argumento de ruta sin sanitizar. */
    protected String argRaw(int index)  { return model.argRaw(index); }

    // ── Sesiones ──────────────────────────────────────────────────────────

    protected void   sessionStart(int maxIdleSeconds)         { model.request.getSession().setMaxInactiveInterval(maxIdleSeconds); }
    protected void   sessionSet(String key, Object value)     { model.request.getSession().setAttribute(key, value); }
    protected Object sessionGet(String key)                   { HttpSession s = model.request.getSession(false); return s != null ? s.getAttribute(key) : null; }
    protected void   sessionDestroy()                         { HttpSession s = model.request.getSession(false); if (s != null) s.invalidate(); }

    // ── Respuestas ────────────────────────────────────────────────────────

    protected final ActionResult view(String path)              { return ActionResult.view(path); }
    protected final ActionResult view(String ctrl, String act)  { return ActionResult.view(ctrl + "/" + act); }
    protected final ActionResult text(String body)              { return ActionResult.text(body); }
    protected final ActionResult json(Object obj)               { return ActionResult.json(obj); }
    protected final ActionResult json(String rawJson)           { return ActionResult.json(rawJson); }
    protected final ActionResult redirect(String location)      { return ActionResult.redirect(location); }
    protected final ActionResult ok()                           { return ActionResult.text("OK"); }
    protected final ActionResult noContent()                    { view.status(204); return ActionResult.text(""); }

    // ── Base de datos ─────────────────────────────────────────────────────

    protected JxDB db() { return new JxDB(); }

    // ── Auth hook ─────────────────────────────────────────────────────────

    /**
     * Sobrescribir para implementar lógica de autenticación por controlador.
     * También se puede usar {@link JxSecurity#setProvider} para autenticación global.
     *
     * @param requiredRoles roles definidos en {@code @JxAuth} (puede estar vacío)
     * @return {@code true} para permitir el acceso
     */
    protected boolean onAuth(String[] requiredRoles) { return true; }

    // ── API PascalCase — primera clase ────────────────────────────────────

    // Parámetros
    protected String  Post(String name)               { return post(name); }
    protected String  Param(String name)              { return param(name); }
    protected String  Arg(int index)                  { return arg(index); }
    protected String  ArgRaw(int index)               { return argRaw(index); }

    // Sesiones
    protected void    SessionCreate(int maxIdleSecs)  { sessionStart(maxIdleSecs); }
    protected void    SetSessVar(String key, Object v){ sessionSet(key, v); }
    protected Object  GetSessVar(String key)          { return sessionGet(key); }
    protected void    SessionDestroy()                { sessionDestroy(); }

    // Respuestas
    protected final ActionResult View(String path)    { return view(path); }
    protected final ActionResult Text(String body)    { return text(body); }
    protected final ActionResult Json(Object obj)     { return json(obj); }
    protected final ActionResult Json(String raw)     { return json(raw); }
    protected final ActionResult Redirect(String loc) { return redirect(loc); }
}
