/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Codificación de salida para prevenir XSS — la defensa correcta se aplica al
 * renderizar, no al recibir la entrada.
 *
 * <p>Expuesto como función EL {@code jx:esc} para usar en JSP:
 * <pre>
 *   &lt;%@ taglib prefix="jx" uri="http://jxmvc/tags" %&gt;
 *   &lt;p&gt;${jx:esc(nombreUsuario)}&lt;/p&gt;
 * </pre>
 */
public final class JxHtml {

    private JxHtml() {}

    /** Escapa texto para insertarlo con seguridad en el cuerpo o atributos HTML. */
    public static String escape(Object value) {
        if (value == null) return "";
        String s = value.toString();
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&'  -> sb.append("&amp;");
                case '<'  -> sb.append("&lt;");
                case '>'  -> sb.append("&gt;");
                case '"'  -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                case '/'  -> sb.append("&#47;");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
