package corvo.web;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Huella del contenido de los recursos, para colgarla de sus URL.
 *
 * <p>Sin esto el navegador se queda con la hoja de estilo de la visita anterior: se sirve con
 * ETag pero sin {@code Cache-Control}, así que decide él cuándo revalidar y a veces no lo hace en
 * horas. Con {@code ?v=} en la dirección, cambiar el archivo cambia la URL y no hay nada que
 * revalidar. Se calcula una vez al arrancar.
 */
final class Huella {

    private static final String[] RECURSOS = {"estaticos/corvo.css", "estaticos/corvo.js",
            "estaticos/terminal.js"};

    static final String ACTUAL = calcular();

    private Huella() {
    }

    private static String calcular() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String recurso : RECURSOS) {
                try (InputStream entrada = Huella.class.getClassLoader().getResourceAsStream(recurso)) {
                    if (entrada != null) {
                        sha.update(entrada.readAllBytes());
                    }
                }
            }
            return HexFormat.of().formatHex(sha.digest()).substring(0, 8);
        } catch (NoSuchAlgorithmException | IOException fallo) {
            // Sin huella el sitio funciona igual; solo se pierde el desalojo de la caché.
            return "0";
        }
    }
}
