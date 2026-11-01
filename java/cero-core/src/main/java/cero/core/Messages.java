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

public final class Messages {

    private final Map<String, Properties> porIdioma = new LinkedHashMap<>();
    private final Map<String, String> resueltos = new ConcurrentHashMap<>();
    private final Set<String> avisados = ConcurrentHashMap.newKeySet();
    private String base = "es";

    private Messages() {
    }

    public static Messages from(String nombre, String... idiomas) {
        Messages m = new Messages();
        m.cargar(m.base, "/" + nombre + ".properties", false);
        for (String idioma : idiomas) {
            m.cargar(idioma, "/" + nombre + "." + idioma + ".properties", true);
        }
        return m;
    }

    public Messages base(String idioma) {
        if (idioma.equals(base)) {
            return this;
        }
        Properties delBase = porIdioma.remove(base);
        base = idioma;
        if (delBase != null) {
            porIdioma.merge(idioma, delBase, (existente, llegado) -> {
                llegado.putAll(existente);
                return llegado;
            });
        }
        resueltos.clear();
        return this;
    }

    public Set<String> idiomas() {
        return porIdioma.keySet();
    }

    public String idiomaBase() {
        return base;
    }

    public String get(String idioma, String clave, Object... argumentos) {
        String plantilla = buscar(idioma, clave);
        if (plantilla == null) {
            if (avisados.add(idioma + "|" + clave)) {
                System.err.println("cero: falta el texto '" + clave + "' en '" + idioma + "'");
            }
            return clave;
        }
        return argumentos.length == 0 ? plantilla
                : new MessageFormat(plantilla, Locale.forLanguageTag(idioma)).format(argumentos);
    }

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
            int guion = c.idioma().indexOf('-');
            if (guion > 0 && porIdioma.containsKey(c.idioma().substring(0, guion))) {
                return c.idioma().substring(0, guion);
            }
        }
        return base;
    }

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

    private void cargar(String idioma, String recurso, boolean obligatorio) {
        try (InputStream entrada = Messages.class.getResourceAsStream(recurso)) {
            if (entrada == null) {
                if (obligatorio) {
                    throw new IllegalArgumentException("no se encontró " + recurso);
                }
                return;
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(entrada, StandardCharsets.UTF_8));
            porIdioma.merge(idioma, p, (existente, llegado) -> {
                existente.putAll(llegado);
                return existente;
            });
        } catch (IOException cause) {
            throw new IllegalStateException("no se pudo leer " + recurso, cause);
        }
    }
}
