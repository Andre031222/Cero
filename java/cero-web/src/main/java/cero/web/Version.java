package cero.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** La versión del framework, leída del pom por el filtrado de recursos. */
final class Version {

    /** Un número escrito a mano en dos sitios acaba siendo dos números distintos. */
    static final String ACTUAL = leer();

    private Version() {
    }

    private static String leer() {
        try (InputStream entrada = Version.class.getClassLoader()
                .getResourceAsStream("cero.properties")) {
            if (entrada == null) {
                return "0.0.0";
            }
            Properties propiedades = new Properties();
            propiedades.load(entrada);
            return propiedades.getProperty("version", "0.0.0");
        } catch (IOException ilegible) {
            return "0.0.0";
        }
    }
}
