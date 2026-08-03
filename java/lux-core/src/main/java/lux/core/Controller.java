package lux.core;

import lux.http.HttpException;
import lux.http.HttpMethod;
import lux.http.Part;
import lux.http.Session;

import java.util.List;

/**
 * Base <b>opcional</b> para controladores.
 *
 * <p>Heredar de aquí no cambia nada de cómo se enrutan los métodos: sigue mandando {@code @Route}
 * y {@code @Get}. Lo único que da es no tener que arrastrar el {@code Context} por parámetro
 * cuando un controlador lo usa mucho, y un sitio donde poner lo que repites — comprobar permisos,
 * armar el modelo común de tus vistas, formatear tus errores.
 *
 * <pre>{@code
 * @Route("/panel")
 * public class PanelController extends Controller {
 *
 *     @Get("/perfil")
 *     public Result perfil() {
 *         if (!autenticado()) return redirect("/auth/login");
 *         return view("perfil", Map.of("usuario", usuario().name()));
 *     }
 * }
 * }</pre>
 *
 * <p>Quien no quiera heredar sigue recibiendo el {@code Context} como parámetro y no se entera de
 * que esto existe. Las dos formas conviven en la misma aplicación.
 *
 * <p><b>Por qué no guarda la petición en un campo.</b> Del controlador hay una sola instancia,
 * compartida por todas las peticiones, y LuxCore atiende cada conexión en su propio hilo virtual.
 * Un campo con la petición dentro sería una carrera: dos peticiones a la vez se lo pisarían y una
 * acabaría respondiendo con los datos de la otra. Por eso todo sale de {@link Current}, que es por
 * hilo. Es la diferencia entre esta base y la de un framework pensado para un hilo por petición.
 */
public abstract class Controller {

    // ── la petición ──────────────────────────────────────────────────────────

    protected final Context ctx() {
        return Current.context();
    }

    protected final String path() {
        return ctx().path();
    }

    protected final HttpMethod method() {
        return ctx().method();
    }

    protected final String param(String nombre) {
        return ctx().pathVariable(nombre);
    }

    protected final String query(String nombre) {
        return ctx().query(nombre);
    }

    protected final String query(String nombre, String porDefecto) {
        return ctx().query(nombre, porDefecto);
    }

    protected final String form(String nombre) {
        return ctx().form(nombre);
    }

    protected final String header(String nombre) {
        return ctx().header(nombre);
    }

    protected final String cookie(String nombre) {
        return ctx().cookie(nombre);
    }

    protected final <T> T body(Class<T> tipo) {
        return ctx().body(tipo);
    }

    protected final List<Part> parts() {
        return ctx().parts();
    }

    // ── quién llama ──────────────────────────────────────────────────────────

    protected final Session session() {
        return ctx().session();
    }

    protected final Principal usuario() {
        return ctx().principal();
    }

    protected final boolean autenticado() {
        return ctx().authenticated();
    }

    // ── qué se responde ──────────────────────────────────────────────────────

    protected final Result view(String plantilla, Object modelo) {
        return Result.view(plantilla, modelo);
    }

    protected final Result json(Object valor) {
        return Result.json(valor);
    }

    protected final Result text(String cuerpo) {
        return Result.text(cuerpo);
    }

    protected final Result html(String cuerpo) {
        return Result.html(cuerpo);
    }

    protected final Result redirect(String destino) {
        return Result.redirect(destino);
    }

    protected final Result created(Object valor) {
        return Result.created(valor);
    }

    protected final Result noContent() {
        return Result.noContent();
    }

    protected final Result status(int codigo, String cuerpo) {
        return Result.status(codigo, cuerpo);
    }

    /** Corta la petición: {@code throw fallo(404, "no existe")}. */
    protected final HttpException fallo(int codigo, String mensaje) {
        return new HttpException(codigo, mensaje);
    }
}
