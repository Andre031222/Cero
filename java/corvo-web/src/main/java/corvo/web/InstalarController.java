package corvo.web;

import corvo.core.Get;
import corvo.core.Result;
import corvo.core.Route;
import corvo.http.HttpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Lo que hace que {@code curl -fsSL https://corvo.ginit.dev/instalar | sh} funcione.
 *
 * <p>Los guiones se sirven como texto plano a propósito: si el navegador se los descargara,
 * nadie podría leerlos antes de ejecutarlos, y eso es justo lo que hay que poder hacer.
 */
@Route("/")
public class InstalarController {

    private static final String VERSION = leerVersion();

    @Get("/version")
    public Result version() {
        return Result.text(VERSION).header("Cache-Control", "no-cache");
    }

    @Get("/instalar")
    public Result unix() {
        return guion("instalar.sh");
    }

    @Get("/instalar.ps1")
    public Result windows() {
        return guion("instalar.ps1");
    }

    private static Result guion(String nombre) {
        return Result.text(recurso("guiones/" + nombre))
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Cache-Control", "no-cache");
    }

    private static String recurso(String ruta) {
        try (InputStream entrada = InstalarController.class.getClassLoader().getResourceAsStream(ruta)) {
            if (entrada == null) {
                throw new HttpException(404, "no encuentro " + ruta);
            }
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String leerVersion() {
        try (InputStream entrada = InstalarController.class.getClassLoader()
                .getResourceAsStream("luxcore.properties")) {
            if (entrada == null) {
                return "0.0.0";
            }
            Properties propiedades = new Properties();
            propiedades.load(entrada);
            return propiedades.getProperty("version", "0.0.0");
        } catch (IOException e) {
            return "0.0.0";
        }
    }
}
