/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Contexto de la respuesta HTTP.
 *
 * <pre>
 *   view.status(201);
 *   view.header("X-Custom", "value");
 *   view.json(rowSet);          // application/json automático
 *   view.html("&lt;h1&gt;Hola&lt;/h1&gt;");
 *   view.redirect("/home");
 * </pre>
 */
public final class JxResponse {

    private final HttpServletRequest  request;
    private final HttpServletResponse response;
    private final String contextPath;
    private boolean manualFormat;
    private boolean bodyWritten;

    public JxResponse(HttpServletRequest request, HttpServletResponse response, String contextPath) {
        this.request     = request;
        this.response    = response;
        this.contextPath = contextPath;
    }

    // ── Control de respuesta ──────────────────────────────────────────────

    public void status(int code)                   { response.setStatus(code); }
    public void header(String name, String value)  { response.setHeader(name, value); }
    public void contentType(String mime)           { response.setContentType(mime); manualFormat = true; }
    public void setFormat(String mime)             { contentType(mime); }  // alias

    // ── Escritura del body ────────────────────────────────────────────────

    public void print(String buffer) {
        try { response.getWriter().print(buffer); bodyWritten = true; }
        catch (IOException ex) { throw new IllegalStateException("Error al escribir respuesta", ex); }
    }

    public void text(String body) throws IOException {
        if (!manualFormat) response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(body);
        bodyWritten = true;
    }

    public void html(String body) throws IOException {
        if (!manualFormat) response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(body);
        bodyWritten = true;
    }

    /**
     * Envía datos binarios como descarga de archivo.
     *
     * <pre>
     *   byte[] zip = ProjectZipBuilder.build(...);
     *   view.raw(zip, "application/zip", "mi-proyecto.zip");
     *   return null;
     * </pre>
     */
    public void raw(byte[] data, String contentType, String fileName) throws IOException {
        response.setContentType(contentType);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + sanitizeFileName(fileName) + "\"");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        bodyWritten = true;
    }

    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "download";
        return fileName.replaceAll("[\\r\\n\"\\\\;/]", "_");
    }

    /** Serializa el objeto con {@link JxJson} y envía {@code application/json}. */
    public void json(Object obj) throws IOException {
        if (!manualFormat) response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JxJson.toJson(obj));
        bodyWritten = true;
    }

    /** Envía un JSON ya serializado. */
    public void json(String rawJson) throws IOException {
        if (!manualFormat) response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(rawJson);
        bodyWritten = true;
    }

    // ── Redirección ───────────────────────────────────────────────────────

    /** Destinos externos (esquema absoluto o {@code //host}) solo con {@code jxmvc.redirect.external=true}. */
    private static final boolean EXTERNAL_REDIRECT =
            BaseDbResolver.propertyBool("jxmvc.redirect.external", false);

    /**
     * Valida el destino de una redirección: los destinos externos se bloquean por
     * defecto para impedir <i>open redirect</i> cuando el destino deriva de entrada
     * del usuario. Se habilitan globalmente con {@code jxmvc.redirect.external=true}.
     */
    static String checkRedirect(String location) {
        if (location == null || location.isBlank()) return "/";
        String t = location.trim();
        boolean external = t.startsWith("//") || t.matches("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.*");
        if (external && !EXTERNAL_REDIRECT)
            throw new JxException(400, "Redirección externa no permitida");
        return t;
    }

    public void redirect(String location) throws IOException {
        location = checkRedirect(location);
        if (location.startsWith("http://") || location.startsWith("https://")) {
            response.sendRedirect(location);
        } else {
            if (!location.startsWith("/")) location = "/" + location;
            response.sendRedirect(contextPath + location);
        }
    }

    HttpServletResponse raw()   { return response; }
    boolean hasBodyWritten()    { return bodyWritten; }
}
