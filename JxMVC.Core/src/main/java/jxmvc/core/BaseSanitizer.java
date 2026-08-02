/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  v3.1.1   : R. Andre Vilca Solorzano

package jxmvc.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Limpieza de entradas para prevenir XSS y ataques de inyección.
 * Se aplica automáticamente en {@link JxRequest#param} y {@link JxRequest#arg}.
 * Usar {@code paramRaw()} / {@code argRaw()} para datos sin limpiar.
 *
 * <p>Vectores cubiertos:
 * <ul>
 *   <li>Script injection: {@code <script>alert(1)</script>}
 *   <li>Event handlers: {@code onerror=}, {@code onclick=}
 *   <li>JavaScript protocol: {@code javascript:void(0)}
 *   <li>Data URI: {@code data:text/html,...}
 *   <li>CSS injection: {@code <style>...}
 *   <li>HTML tags genéricos
 *   <li>Doble URL encoding: {@code %253Cscript%253E}
 *   <li>Entidades HTML: {@code &lt;script&gt;}
 * </ul>
 */
public final class BaseSanitizer {

    private BaseSanitizer() {}

    // Patterns pre-compilados — Pattern.compile() es costoso, compartir entre hilos es seguro
    private static final Pattern P_SCRIPT   = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern P_STYLE    = Pattern.compile("(?is)<style[^>]*>.*?</style>");
    private static final Pattern P_IFRAME   = Pattern.compile("(?is)<iframe[^>]*>.*?</iframe>");
    private static final Pattern P_OBJECT   = Pattern.compile("(?is)<object[^>]*>.*?</object>");
    private static final Pattern P_EMBED    = Pattern.compile("(?is)<embed[^>]*>");
    private static final Pattern P_JS_PROTO = Pattern.compile("(?i)javascript\\s*:");
    private static final Pattern P_DATA_URI = Pattern.compile("(?i)data\\s*:\\s*text/html");
    private static final Pattern P_VB_PROTO = Pattern.compile("(?i)vbscript\\s*:");
    private static final Pattern P_ON_EVENT = Pattern.compile("(?i)on[a-z]{2,20}\\s*=");
    private static final Pattern P_TAGS     = Pattern.compile("<[^>]{0,500}>");
    private static final Pattern P_SPACES   = Pattern.compile("\\s{2,}");

    public static String clean(String input) {
        if (input == null) return null;

        String v = decodeRepeatedly(input);
        v = decodeHtmlEntities(v);

        v = P_SCRIPT.matcher(v).replaceAll(" ");
        v = P_STYLE.matcher(v).replaceAll(" ");
        v = P_IFRAME.matcher(v).replaceAll(" ");
        v = P_OBJECT.matcher(v).replaceAll(" ");
        v = P_EMBED.matcher(v).replaceAll(" ");
        v = P_JS_PROTO.matcher(v).replaceAll(" ");
        v = P_DATA_URI.matcher(v).replaceAll(" ");
        v = P_VB_PROTO.matcher(v).replaceAll(" ");
        v = P_ON_EVENT.matcher(v).replaceAll(" ");
        v = P_TAGS.matcher(v).replaceAll(" ");
        v = v.replace("<", " ").replace(">", " ");
        v = P_SPACES.matcher(v).replaceAll(" ").trim();

        return v;
    }

    /**
     * Versión ligera para campos que no son HTML — solo decodifica y normaliza.
     * Más rápido que {@link #clean} para IDs, emails, números.
     */
    public static String cleanBasic(String input) {
        if (input == null) return null;
        return input.trim()
                    .replace("<", "")
                    .replace(">", "")
                    .replace("\"", "")
                    .replace("'", "");
    }

    // ── Privados ──────────────────────────────────────────────────────────

    private static String decodeRepeatedly(String input) {
        String current = input;
        for (int i = 0; i < 3; i++) {
            String decoded = decodeOnce(current);
            if (decoded.equals(current)) break;
            current = decoded;
        }
        return current;
    }

    private static String decodeOnce(String input) {
        try { return URLDecoder.decode(input, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ex) { return input; }
    }

    private static String decodeHtmlEntities(String v) {
        return v.replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&quot;", "\"")
                .replace("&#39;",  "'")
                .replace("&apos;", "'")
                .replace("&amp;",  "&")
                .replace("&#x3c;", "<")
                .replace("&#x3e;", ">")
                .replace("&#60;",  "<")
                .replace("&#62;",  ">")
                .replace("&#x27;", "'")
                .replace("&#x22;", "\"");
    }
}
