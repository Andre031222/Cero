package cero.core;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Limpieza de HTML de fuera: se reconoce la entrada y se reescribe la salida solo con lo
 * permitido. Nada pasa tal cual. Guía: https://cero.ginit.dev/guia#entradas
 */
public final class Sanitize {

    /** Lo que se puede emitir. */
    private static final Set<String> ETIQUETAS = Set.of(
            "p", "br", "hr", "span", "div", "b", "strong", "i", "em", "u", "s", "sub", "sup",
            "small", "mark", "abbr", "blockquote", "q", "cite", "code", "pre", "kbd", "samp",
            "var", "ul", "ol", "li", "dl", "dt", "dd", "a", "img", "figure", "figcaption",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col");

    /** Atributos admitidos por etiqueta. Lo que no esté aquí no se emite. */
    private static final Map<String, Set<String>> ATRIBUTOS = Map.of(
            "a", Set.of("href", "title"),
            "img", Set.of("src", "alt", "title", "width", "height"),
            "td", Set.of("colspan", "rowspan"),
            "th", Set.of("colspan", "rowspan", "scope"),
            "col", Set.of("span"),
            "colgroup", Set.of("span"),
            "abbr", Set.of("title"),
            "q", Set.of("cite"),
            "blockquote", Set.of("cite"));

    /** Atributos cuyo valor es una dirección. */
    private static final Set<String> DIRECCIONES = Set.of("href", "src", "cite");

    /** Esquemas admitidos en una dirección absoluta. */
    private static final Set<String> ESQUEMAS = Set.of("http", "https", "mailto", "tel");

    /** No llevan cierre. */
    private static final Set<String> VACIAS = Set.of("br", "hr", "img", "col");

    /** Se van con su contenido dentro, no solo la etiqueta. */
    private static final Set<String> CON_CONTENIDO = Set.of(
            "script", "style", "iframe", "frame", "frameset", "object", "embed", "applet",
            "noscript", "template", "svg", "math", "title", "textarea", "xmp");

    private static final int MAX_ANIDAMIENTO = 100;

    private Sanitize() {
    }

    /** HTML limpio, conservando el formato admitido. */
    public static String html(String input) {
        return recorrer(input, true);
    }

    /** El mismo texto sin ninguna etiqueta, con los espacios recogidos. */
    public static String text(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return recorrer(input, false).replaceAll("\\s{2,}", " ").trim();
    }

    /** Un nombre de archivo que no puede salirse de su carpeta ni esconderse. */
    public static String filename(String input) {
        if (input == null) {
            return null;
        }
        String name = input.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? "archivo" : name;
    }

    // ─── el recorrido ────────────────────────────────────────────────────────────────────────

    private static String recorrer(String entrada, boolean conEtiquetas) {
        if (entrada == null || entrada.isEmpty()) {
            return entrada;
        }
        StringBuilder salida = new StringBuilder(entrada.length());
        // Cierres pendientes, del más reciente al más antiguo.
        ArrayDeque<String> abiertas = new ArrayDeque<>();
        int i = 0;

        while (i < entrada.length()) {
            char c = entrada.charAt(i);
            if (c != '<') {
                escapar(c, salida, conEtiquetas);
                i++;
                continue;
            }
            // Un '<' que no abre nada reconocible es texto: `3 < 5`.
            if (i + 1 >= entrada.length()) {
                escapar('<', salida, conEtiquetas);
                break;
            }
            char siguiente = entrada.charAt(i + 1);

            if (siguiente == '!' || siguiente == '?') {
                i = finDeComentario(entrada, i);
                continue;
            }
            if (siguiente == '/') {
                int fin = finDeEtiqueta(entrada, i);
                String nombre = nombreDeCierre(entrada, i, fin);
                if (nombre == null) {
                    escapar('<', salida, conEtiquetas);
                    i++;
                    continue;
                }
                if (conEtiquetas) {
                    cerrarHasta(nombre, abiertas, salida);
                }
                i = fin;
                continue;
            }
            if (!Character.isLetter(siguiente)) {
                escapar('<', salida, conEtiquetas);
                i++;
                continue;
            }

            Etiqueta etiqueta = Etiqueta.leer(entrada, i);
            if (etiqueta == null) {
                escapar('<', salida, conEtiquetas);
                i++;
                continue;
            }
            i = etiqueta.fin;

            if (CON_CONTENIDO.contains(etiqueta.nombre)) {
                // Sin cierre, se descarta lo que queda.
                i = etiqueta.autocerrada ? i : saltarContenido(entrada, i, etiqueta.nombre);
                continue;
            }
            if (!conEtiquetas || !ETIQUETAS.contains(etiqueta.nombre)) {
                // Etiqueta desconocida: se cae ella, su texto se queda.
                continue;
            }
            boolean cierra = !VACIAS.contains(etiqueta.nombre) && !etiqueta.autocerrada;
            if (cierra && abiertas.size() >= MAX_ANIDAMIENTO) {
                continue;
            }
            emitir(etiqueta, salida);
            if (cierra) {
                abiertas.push(etiqueta.nombre);
            }
        }

        while (!abiertas.isEmpty()) {
            salida.append("</").append(abiertas.pop()).append('>');
        }
        return salida.toString();
    }

    private static void escapar(char c, StringBuilder salida, boolean conEtiquetas) {
        if (!conEtiquetas) {
            salida.append(c);
            return;
        }
        switch (c) {
            case '&' -> salida.append("&amp;");
            case '<' -> salida.append("&lt;");
            case '>' -> salida.append("&gt;");
            case '"' -> salida.append("&quot;");
            case '\'' -> salida.append("&#39;");
            default -> salida.append(c);
        }
    }

    private static void emitir(Etiqueta etiqueta, StringBuilder salida) {
        salida.append('<').append(etiqueta.nombre);
        Set<String> admitidos = ATRIBUTOS.getOrDefault(etiqueta.nombre, Set.of());
        boolean enlace = false;

        for (Map.Entry<String, String> atributo : etiqueta.atributos.entrySet()) {
            String nombre = atributo.getKey();
            if (!admitidos.contains(nombre)) {
                continue;
            }
            String valor = atributo.getValue();
            if (DIRECCIONES.contains(nombre)) {
                valor = direccionSegura(valor);
                if (valor == null) {
                    continue;
                }
                enlace |= nombre.equals("href");
            }
            salida.append(' ').append(nombre).append("=\"");
            for (int i = 0; i < valor.length(); i++) {
                escapar(valor.charAt(i), salida, true);
            }
            salida.append('"');
        }
        // Un enlace de fuera no presta reputación ni entrega el `window.opener`.
        if (etiqueta.nombre.equals("a") && enlace) {
            salida.append(" rel=\"nofollow noopener noreferrer\"");
        }
        salida.append('>');
    }

    /** La dirección tal cual si es admisible, o {@code null}. Se juzga como la leerá el navegador. */
    private static String direccionSegura(String valor) {
        String limpia = sinControles(entidades(valor)).trim();
        if (limpia.isEmpty()) {
            return null;
        }
        int colon = limpia.indexOf(':');
        int barra = limpia.indexOf('/');
        int almohadilla = limpia.indexOf('#');
        int interrogante = limpia.indexOf('?');

        // El ':' solo es esquema si va antes de todo separador: en `foo/bar:baz` no lo es.
        boolean tieneEsquema = colon >= 0
                && (barra < 0 || colon < barra)
                && (almohadilla < 0 || colon < almohadilla)
                && (interrogante < 0 || colon < interrogante);
        if (!tieneEsquema) {
            return valor;
        }
        String esquema = limpia.substring(0, colon).toLowerCase(Locale.ROOT);
        return ESQUEMAS.contains(esquema) ? valor : null;
    }

    /** Quita todo lo que el navegador ignoraría al leer una dirección. */
    private static String sinControles(String texto) {
        StringBuilder limpia = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c > 0x20 && c != 0x7F) {
                limpia.append(c);
            }
        }
        return limpia.toString();
    }

    /** Deshace las entidades que el navegador entendería dentro del valor de un atributo. */
    private static String entidades(String texto) {
        if (texto.indexOf('&') < 0) {
            return texto;
        }
        StringBuilder salida = new StringBuilder(texto.length());
        int i = 0;
        while (i < texto.length()) {
            char c = texto.charAt(i);
            if (c != '&') {
                salida.append(c);
                i++;
                continue;
            }
            // El navegador acepta la entidad sin el ';' final; aquí también.
            int fin = i + 1;
            while (fin < texto.length() && fin - i <= 10 && texto.charAt(fin) != ';'
                    && texto.charAt(fin) != '&' && !Character.isWhitespace(texto.charAt(fin))) {
                fin++;
            }
            String cuerpo = texto.substring(i + 1, fin);
            int saltar = fin < texto.length() && texto.charAt(fin) == ';' ? fin + 1 : fin;
            Character resuelta = resolver(cuerpo);
            if (resuelta == null) {
                salida.append(c);
                i++;
            } else {
                salida.append(resuelta.charValue());
                i = saltar;
            }
        }
        return salida.toString();
    }

    private static Character resolver(String cuerpo) {
        if (cuerpo.isEmpty()) {
            return null;
        }
        if (cuerpo.charAt(0) == '#') {
            try {
                String digitos = cuerpo.substring(1);
                if (digitos.isEmpty()) {
                    return null;
                }
                int valor = digitos.charAt(0) == 'x' || digitos.charAt(0) == 'X'
                        ? Integer.parseInt(digitos.substring(1), 16)
                        : Integer.parseInt(digitos);
                return valor >= 0 && valor <= 0xFFFF ? (char) valor : null;
            } catch (RuntimeException ilegible) {
                return null;
            }
        }
        return switch (cuerpo.toLowerCase(Locale.ROOT)) {
            case "amp" -> '&';
            case "lt" -> '<';
            case "gt" -> '>';
            case "quot" -> '"';
            case "apos" -> '\'';
            case "colon" -> ':';
            case "tab" -> '\t';
            case "newline" -> '\n';
            default -> null;
        };
    }

    private static void cerrarHasta(String nombre, ArrayDeque<String> abiertas, StringBuilder salida) {
        if (!abiertas.contains(nombre)) {
            // Cierre huérfano: emitirlo desequilibraría la salida.
            return;
        }
        String cima;
        do {
            cima = abiertas.pop();
            salida.append("</").append(cima).append('>');
        } while (!cima.equals(nombre));
    }

    private static int finDeComentario(String texto, int desde) {
        if (texto.startsWith("<!--", desde)) {
            int cierre = texto.indexOf("-->", desde + 4);
            return cierre < 0 ? texto.length() : cierre + 3;
        }
        int cierre = texto.indexOf('>', desde);
        return cierre < 0 ? texto.length() : cierre + 1;
    }

    private static int finDeEtiqueta(String texto, int desde) {
        int cierre = texto.indexOf('>', desde);
        return cierre < 0 ? texto.length() : cierre + 1;
    }

    private static String nombreDeCierre(String texto, int desde, int fin) {
        int i = desde + 2;
        int inicio = i;
        while (i < fin && (Character.isLetterOrDigit(texto.charAt(i)) || texto.charAt(i) == '-')) {
            i++;
        }
        return i == inicio ? null : texto.substring(inicio, i).toLowerCase(Locale.ROOT);
    }

    /** Salta hasta el cierre de una etiqueta que se lleva su contenido. */
    private static int saltarContenido(String texto, int desde, String nombre) {
        String minusculas = texto.toLowerCase(Locale.ROOT);
        String cierre = "</" + nombre;
        int i = desde;
        while (i < texto.length()) {
            int marca = minusculas.indexOf(cierre, i);
            if (marca < 0) {
                return texto.length();
            }
            int tras = marca + cierre.length();
            if (tras >= texto.length() || texto.charAt(tras) == '>'
                    || Character.isWhitespace(texto.charAt(tras)) || texto.charAt(tras) == '/') {
                return finDeEtiqueta(texto, marca);
            }
            i = marca + 1;
        }
        return texto.length();
    }

    // ─── una etiqueta de apertura, ya leída ──────────────────────────────────────────────────

    private static final class Etiqueta {

        final String nombre;
        final Map<String, String> atributos;
        final boolean autocerrada;
        final int fin;

        private Etiqueta(String nombre, Map<String, String> atributos, boolean autocerrada, int fin) {
            this.nombre = nombre;
            this.atributos = atributos;
            this.autocerrada = autocerrada;
            this.fin = fin;
        }

        /** Lee {@code <nombre a="1" b>} desde {@code desde}, o {@code null} si no lo es. */
        static Etiqueta leer(String texto, int desde) {
            int i = desde + 1;
            int inicio = i;
            while (i < texto.length() && (Character.isLetterOrDigit(texto.charAt(i))
                    || texto.charAt(i) == '-')) {
                i++;
            }
            if (i == inicio) {
                return null;
            }
            String nombre = texto.substring(inicio, i).toLowerCase(Locale.ROOT);
            Map<String, String> atributos = new LinkedHashMap<>();
            boolean autocerrada = false;

            while (i < texto.length()) {
                while (i < texto.length() && esSeparador(texto.charAt(i))) {
                    i++;
                }
                if (i >= texto.length()) {
                    break;
                }
                char c = texto.charAt(i);
                if (c == '>') {
                    i++;
                    break;
                }
                if (c == '/') {
                    autocerrada = true;
                    i++;
                    continue;
                }
                int nombreInicio = i;
                while (i < texto.length() && !esSeparador(texto.charAt(i)) && texto.charAt(i) != '='
                        && texto.charAt(i) != '>' && texto.charAt(i) != '/') {
                    i++;
                }
                if (i == nombreInicio) {
                    i++;
                    continue;
                }
                String atributo = texto.substring(nombreInicio, i).toLowerCase(Locale.ROOT);
                while (i < texto.length() && esSeparador(texto.charAt(i))) {
                    i++;
                }
                String valor = "";
                if (i < texto.length() && texto.charAt(i) == '=') {
                    i++;
                    while (i < texto.length() && esSeparador(texto.charAt(i))) {
                        i++;
                    }
                    if (i < texto.length() && (texto.charAt(i) == '"' || texto.charAt(i) == '\'')) {
                        char comilla = texto.charAt(i);
                        int valorInicio = ++i;
                        while (i < texto.length() && texto.charAt(i) != comilla) {
                            i++;
                        }
                        valor = texto.substring(valorInicio, Math.min(i, texto.length()));
                        i = Math.min(i + 1, texto.length());
                    } else {
                        int valorInicio = i;
                        while (i < texto.length() && !esSeparador(texto.charAt(i))
                                && texto.charAt(i) != '>') {
                            i++;
                        }
                        valor = texto.substring(valorInicio, i);
                    }
                }
                atributos.putIfAbsent(atributo, valor);
            }
            return new Etiqueta(nombre, atributos, autocerrada, i);
        }

        /** La barra separa, como en el navegador: `<svg/onload=…>` es svg con atributo onload. */
        private static boolean esSeparador(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
        }
    }
}
