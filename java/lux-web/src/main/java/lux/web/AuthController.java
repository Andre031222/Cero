package lux.web;

import lux.core.Context;
import lux.core.Form;
import lux.core.Get;
import lux.core.Inject;
import lux.core.Log;
import lux.core.OAuth;
import lux.core.Post;
import lux.core.RequireAuth;
import lux.core.Result;
import lux.core.Route;
import lux.http.Session;

import java.util.Map;

@Route("/auth")
public class AuthController {

    private static final Log log = Log.of(AuthController.class);

    @Inject
    Autenticacion auth;

    @Get("/login")
    public Result formulario(Context contexto) {
        if (Autenticacion.actual(contexto) != null) {
            return Result.redirect("/auth/perfil");
        }
        Map<String, Object> modelo = Vista.modelo(contexto, "Iniciar sesión · LuxCore");
        modelo.put("googleDisponible", auth.googleDisponible());
        modelo.put("error", mensajeDeError(contexto.query("error")));
        return Result.view("login", modelo);
    }

    @Post("/login")
    public Result entrar(Context contexto, @Form("email") String email,
                         @Form("password") String contrasena) {

        Usuario usuario = auth.porContrasena(email, contrasena);
        if (usuario == null) {
            return Result.redirect("/auth/login?error=credenciales");
        }
        contexto.session().set(Autenticacion.CLAVE_USUARIO, usuario);
        return Result.redirect("/auth/perfil");
    }

    @Get("/google")
    public Result google(Context contexto) {
        if (!auth.googleDisponible()) {
            return Result.redirect("/auth/login?error=google_off");
        }
        OAuth.Salida salida = auth.google().autorizar();
        Session sesion = contexto.session();
        sesion.set(Autenticacion.CLAVE_ESTADO, salida.estado());
        sesion.set(Autenticacion.CLAVE_VERIFICADOR, salida.verificador());
        return Result.redirectExternal(salida.url());
    }

    @Get("/google/callback")
    public Result callback(Context contexto) {
        String fallo = contexto.query("error");
        if (fallo != null && !fallo.isBlank()) {
            return Result.redirect("/auth/login?error=google_cancel");
        }
        Session sesion = contexto.session();
        Object esperado = sesion.get(Autenticacion.CLAVE_ESTADO);
        if (esperado == null || !esperado.equals(contexto.query("state"))) {
            return Result.redirect("/auth/login?error=state");
        }
        try {
            String verificador = (String) sesion.get(Autenticacion.CLAVE_VERIFICADOR);
            OAuth.Identidad identidad = auth.google()
                    .intercambiar(contexto.query("code"), verificador);
            sesion.set(Autenticacion.CLAVE_USUARIO, auth.desdeGoogle(identidad));
        } catch (RuntimeException roto) {
            log.warn("falló la vuelta de Google: {}", roto);
            return Result.redirect("/auth/login?error=google_fail");
        } finally {
            sesion.remove(Autenticacion.CLAVE_ESTADO);
            sesion.remove(Autenticacion.CLAVE_VERIFICADOR);
        }
        return Result.redirect("/auth/perfil");
    }

    @RequireAuth
    @Get("/perfil")
    public Result perfil(Context contexto) {
        Map<String, Object> modelo = Vista.modelo(contexto, "Mi perfil · LuxCore");
        modelo.put("usuario", Autenticacion.actual(contexto));
        return Result.view("perfil", modelo);
    }

    @Get("/logout")
    public Result salir(Context contexto) {
        Session sesion = contexto.session(false);
        if (sesion != null) {
            sesion.invalidate();
        }
        return Result.redirect("/");
    }

    private static String mensajeDeError(String codigo) {
        if (codigo == null) {
            return null;
        }
        return switch (codigo) {
            case "credenciales" -> "Correo o contraseña incorrectos.";
            case "google_off" -> "El acceso con Google no está configurado en este servidor.";
            case "google_cancel" -> "Cancelaste el acceso con Google.";
            case "google_fail" -> "Google no pudo confirmar tu identidad.";
            case "state" -> "La sesión de acceso caducó. Inténtalo otra vez.";
            default -> "No se pudo iniciar sesión.";
        };
    }
}
