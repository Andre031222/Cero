package cero.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Textos en varios idiomas, leídos de {@code .properties} del classpath.
 *
 * <pre>
 *   mensajes.properties       ← el idioma base
 *   mensajes.en.properties
 *   mensajes.qu.properties
 * </pre>
 *
 * <pre>
 *   Messages textos = Messages.from("mensajes").base("es");
 *   Cero.app().messages(textos).start();
 *
 *   context.t("saludo", nombre)      // «Hola, Richar»
 * </pre>
 *
 * <p>No usa {@link java.util.ResourceBundle} a propósito. ResourceBundle cae al idioma
 * <em>del sistema</em> cuando no encuentra una clave, así que el mismo despliegue responde
 * distinto según cómo esté configurado el servidor — un fallo que no se reproduce en la máquina
 * de nadie. Aquí la cadena de respaldo está escrita y es la misma en todas partes.
 */
public final class Messages {

    private final Map<String, Properties> porIdioma = new LinkedHashMap<>();
    private final Map<String, String> resueltos = new ConcurrentHashMap<>();
    private final Set<String> avisados = ConcurrentHashMap.newKeySet();
    private String base = "es";

    private Messages() {
    }

    /**
     * Carga {@code <nombre>.properties} y todos sus {@code <nombre>.<idioma>.properties}.
     *
     * <p>Los idiomas hay que nombrarlos: el classpath no se puede listar de forma fiable cuando
     * la aplicación va dentro de un jar, así que descubrirlos solos funcionaría en desarrollo y
     * fallaría al desplegar. Vale más pedirlos que fallar solo en producción.
     */
    public static Messages from(String nombre, String... idiomas) {
        Messages m = new Messages();
        m.cargar(nombre, "", "/" + nombre + ".properties");
        for (String idioma : idiomas) {
            m.cargar(nombre, idioma, "/" + nombre + "." + idioma + ".properties");
        }
        return m;
    }

    /** El idioma del archivo sin sufijo, y el último recurso de la cadena. */
    public Messages base(String idioma) {
        base = idioma;
        Properties sinSufijo = porIdioma.remove("");
        if (sinSufijo != null) {
            porIdioma.merge(idioma, sinSufijo, (nuevo, viejo) -> {
                viejo.putAll(nuevo);
                return viejo;
            });
        }
        return this;
    }

    /** Los idiomas cargados, para negociar contra {@code Accept-Language}. */
    public Set<String> idiomas() {
        return porIdioma.keySet();
    }

    public String idiomaBase() {
        return base;
    }

    /**
     * El texto de una clave, con sus argumentos puestos.
     *
     * <p>La cadena de respaldo es idioma pedido → idioma base → la propia clave. Devolver la
     * clave y no lanzar es deliberado: un texto que falta no debe tumbar una página entera. Pero
     * tampoco debe pasar inadvertido, así que la primera vez que ocurre se avisa por consola —
     * una vez por clave, no en cada petición, que si no el aviso se convierte en ruido y deja de
     * leerse.
     */
    public String get(String idioma, String clave, Object... argumentos) {
        String plantilla = buscar(idioma, clave);
        if (plantilla == null) {
            if (avisados.add(idioma + "|" + clave)) {
                System.err.println("cero: falta el texto '" + clave + "' en '" + idioma + "'");
            }
            return clave;
        }
        // MessageFormat solo si hay algo que poner: reservar un formateador por cada texto fijo
        // es trabajo para nada, y además MessageFormat trata las comillas simples como escape,
        // lo que estropearía un «d'accord» que no lleva argumentos.
        return argumentos.length == 0 ? plantilla
                : new MessageFormat(plantilla, Locale.forLanguageTag(idioma)).format(argumentos);
    }

    /**
     * Elige idioma leyendo {@code Accept-Language} contra lo que hay cargado.
     *
     * <p>Se respeta el factor de calidad —{@code es;q=0.9}— porque es lo que separa «prefiero
     * español» de «acepto español a regañadientes», y ordenar por posición se equivoca justo en
     * ese caso. Si nada encaja, el idioma base.
     */
    public String negociar(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return base;
        }
        record Candidato(String idioma, double calidad) {
        }
        List<Candidato> candidatos = new ArrayList<>();
        for (String trozo : acceptLanguage.split(",")) {
            String[] partes = trozo.trim().split(";");
            String etiqueta = partes[0].trim().toLowerCase(Locale.ROOT);
            if (etiqueta.isEmpty()) {
                continue;
            }
            double q = 1.0;
            for (int i = 1; i < partes.length; i++) {
                String p = partes[i].trim();
                if (p.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(p.substring(2));
                    } catch (NumberFormatException ignorado) {
                        q = 0;
                    }
                }
            }
            candidatos.add(new Candidato(etiqueta, q));
        }
        candidatos.sort((a, b) -> Double.compare(b.calidad(), a.calidad()));
        for (Candidato c : candidatos) {
            if (c.calidad() <= 0) {
                continue;
            }
            if (porIdioma.containsKey(c.idioma())) {
                return c.idioma();
            }
            // «es-PE» vale para «es»: un idioma regional que no tenemos cae en su idioma.
            int guion = c.idioma().indexOf('-');
            if (guion > 0 && porIdioma.containsKey(c.idioma().substring(0, guion))) {
                return c.idioma().substring(0, guion);
            }
        }
        return base;
    }

    private String buscar(String idioma, String clave) {
        String cacheado = resueltos.get(idioma + "|" + clave);
        if (cacheado != null) {
            return cacheado;
        }
        Properties p = porIdioma.get(idioma);
        String valor = p == null ? null : p.getProperty(clave);
        if (valor == null && !base.equals(idioma)) {
            Properties b = porIdioma.get(base);
            valor = b == null ? null : b.getProperty(clave);
        }
        if (valor != null) {
            resueltos.put(idioma + "|" + clave, valor);
        }
        return valor;
    }

    private void cargar(String nombre, String idioma, String recurso) {
        try (InputStream entrada = Messages.class.getResourceAsStream(recurso)) {
            if (entrada == null) {
                if (!idioma.isEmpty()) {
                    throw new IllegalArgumentException("no se encontró " + recurso);
                }
                return;
            }
            Properties p = new Properties();
            // UTF-8 explícito. Java lee .properties en UTF-8 desde la 9, pero decirlo aquí
            // quita la duda: estos archivos llevan acentos y eñes en todas las líneas.
            p.load(new InputStreamReader(entrada, StandardCharsets.UTF_8));
            porIdioma.put(idioma, p);
        } catch (IOException cause) {
            throw new IllegalStateException("no se pudo leer " + recurso, cause);
        }
    }
}
