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
     *
     * <p>El archivo sin sufijo entra directamente con el nombre del idioma base. Antes se
     * guardaba bajo una clave vacía a la espera de {@link #base}, y quien no llamara a ese
     * método se quedaba sin idioma base sin enterarse: la negociación lo elegía, la búsqueda no
     * lo encontraba y la página salía con los nombres de las claves.
     */
    public static Messages from(String nombre, String... idiomas) {
        Messages m = new Messages();
        m.cargar(m.base, "/" + nombre + ".properties", false);
        for (String idioma : idiomas) {
            m.cargar(idioma, "/" + nombre + "." + idioma + ".properties", true);
        }
        return m;
    }

    /**
     * El idioma del archivo sin sufijo, y el último recurso de la cadena.
     *
     * <p>Solo hace falta si ese archivo no está en castellano, que es lo que se supone por
     * defecto. Llamarlo con {@code "es"} no hace nada.
     */
    public Messages base(String idioma) {
        if (idioma.equals(base)) {
            return this;
        }
        Properties delBase = porIdioma.remove(base);
        base = idioma;
        if (delBase != null) {
            // Si ya había un archivo con el nombre nuevo, ese manda: es más específico que el
            // que venía sin sufijo.
            porIdioma.merge(idioma, delBase, (existente, llegado) -> {
                llegado.putAll(existente);
                return llegado;
            });
        }
        resueltos.clear();
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

    /** Las claves visibles en un idioma: las suyas más las que hereda del base. */
    Set<String> claves(String idioma) {
        Set<String> todas = new java.util.LinkedHashSet<>();
        Properties b = porIdioma.get(base);
        if (b != null) {
            b.stringPropertyNames().forEach(todas::add);
        }
        Properties p = porIdioma.get(idioma);
        if (p != null) {
            p.stringPropertyNames().forEach(todas::add);
        }
        return todas;
    }

    /** Si la clave existe de verdad, sin el respaldo de devolverla como texto. */
    boolean tiene(String idioma, String clave) {
        return buscar(idioma, clave) != null;
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

    /**
     * @param obligatorio un idioma que se pidió por nombre y no está es un error; el archivo sin
     *                    sufijo, en cambio, puede no existir si todo va con sufijo
     */
    private void cargar(String idioma, String recurso, boolean obligatorio) {
        try (InputStream entrada = Messages.class.getResourceAsStream(recurso)) {
            if (entrada == null) {
                if (obligatorio) {
                    throw new IllegalArgumentException("no se encontró " + recurso);
                }
                return;
            }
            Properties p = new Properties();
            // UTF-8 explícito. Java lee .properties en UTF-8 desde la 9, pero decirlo aquí
            // quita la duda: estos archivos llevan acentos y eñes en todas las líneas.
            p.load(new InputStreamReader(entrada, StandardCharsets.UTF_8));
            // Puede haber ya algo con ese nombre: el archivo sin sufijo entró con el del idioma
            // base. Entre los dos, lo que viene con sufijo manda.
            porIdioma.merge(idioma, p, (existente, llegado) -> {
                existente.putAll(llegado);
                return existente;
            });
        } catch (IOException cause) {
            throw new IllegalStateException("no se pudo leer " + recurso, cause);
        }
    }
}
