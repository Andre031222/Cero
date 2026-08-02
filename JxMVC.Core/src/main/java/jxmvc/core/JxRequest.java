/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Contexto de la petición HTTP: parámetros, argumentos de ruta,
 * cabeceras, body y subida de archivos.
 *
 * <pre>
 *   String name  = model.param("name");           // form/query param
 *   int    age   = model.paramInt("age", 0);       // con valor por defecto
 *   String id    = model.arg(0);                   // segmento de ruta (sanitizado)
 *   String raw   = model.argRaw(0);                // sin sanitizar
 *   String body  = model.body();                   // body completo (JSON etc.)
 *   boolean ok   = model.isPost();
 * </pre>
 */
public class JxRequest {

    public final HttpServletRequest  request;
    public final HttpServletResponse response;

    private final String[] args;
    private final Map<String, String> pathVars;
    private String lastError;

    public JxRequest(HttpServletRequest request, HttpServletResponse response,
                     String[] args, Map<String, String> pathVars) {
        this.request  = request;
        this.response = response;
        this.args     = args;
        this.pathVars = pathVars != null ? pathVars : Collections.emptyMap();
    }

    public JxRequest(HttpServletRequest request, HttpServletResponse response, String[] args) {
        this(request, response, args, Collections.emptyMap());
    }

    protected JxRequest() {
        this.request  = null;
        this.response = null;
        this.args     = new String[0];
        this.pathVars = Collections.emptyMap();
    }

    // ── Info de la petición ───────────────────────────────────────────────

    public String  method()    { return request != null ? request.getMethod() : "GET"; }
    public String  path()      { return request != null ? request.getRequestURI() : "/"; }
    public String  ip()        { return request != null ? request.getRemoteAddr() : "127.0.0.1"; }
    public String  header(String name) { return request != null ? request.getHeader(name) : null; }
    public boolean isGet()     { return "GET".equalsIgnoreCase(method()); }
    public boolean isPost()    { return "POST".equalsIgnoreCase(method()); }
    public boolean isPut()     { return "PUT".equalsIgnoreCase(method()); }
    public boolean isDelete()  { return "DELETE".equalsIgnoreCase(method()); }
    public boolean isPatch()   { return "PATCH".equalsIgnoreCase(method()); }

    // ── Parámetros GET/POST (siempre sanitizados) ─────────────────────────

    public String  param(String key)           { return request != null ? BaseSanitizer.clean(request.getParameter(key)) : null; }
    public String  paramRaw(String key)        { return request != null ? request.getParameter(key) : null; }
    public boolean hasParam(String key)        { String v = request != null ? request.getParameter(key) : null; return v != null && !v.isBlank(); }

    public int     paramInt(String key, int def) {
        try { return Integer.parseInt(request.getParameter(key)); } catch (Exception e) { return def; }
    }
    public long    paramLong(String key, long def) {
        try { return Long.parseLong(request.getParameter(key)); } catch (Exception e) { return def; }
    }
    public double  paramDouble(String key, double def) {
        try { return Double.parseDouble(request.getParameter(key)); } catch (Exception e) { return def; }
    }
    public boolean paramBool(String key) {
        String v = request.getParameter(key);
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
    }

    // ── Variables de plantilla /usuarios/{id} ─────────────────────────────

    public String pathVar(String name) {
        return name != null ? pathVars.get(name.toLowerCase()) : null;
    }

    public int    pathVarInt(String name) {
        try { return Integer.parseInt(pathVar(name)); } catch (Exception e) { return 0; }
    }

    public long   pathVarLong(String name) {
        try { return Long.parseLong(pathVar(name)); } catch (Exception e) { return 0L; }
    }

    // ── Argumentos de ruta /ctrl/action/arg0/arg1 ─────────────────────────

    public String   arg(int i)     { return (i < 0 || i >= args.length) ? null : BaseSanitizer.clean(args[i]); }
    public String   argRaw(int i)  { return (i < 0 || i >= args.length) ? null : args[i]; }
    public int      argsCount()    { return args.length; }
    public String[] args() {
        String[] safe = new String[args.length];
        for (int i = 0; i < args.length; i++) safe[i] = BaseSanitizer.clean(args[i]);
        return safe;
    }

    // ── Body de la petición (JSON, texto plano, etc.) ─────────────────────

    /** Tope de lectura del body no-multipart — configurable con {@code jxmvc.body.maxBytes}. */
    static final int MAX_BODY_BYTES =
            BaseDbResolver.propertyInt("jxmvc.body.maxBytes", 10 * 1024 * 1024);

    public String body() {
        if (request == null) return "";
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES)
            throw new JxException(413, "Body demasiado grande (máx " + MAX_BODY_BYTES + " bytes)");
        try {
            byte[] bytes = readCapped(request.getInputStream(), MAX_BODY_BYTES);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (JxException e) { throw e;
        } catch (Exception e) { return null; }
    }

    /** Lee como máximo {@code max} bytes; si el stream trae más, responde 413 (no confía en Content-Length). */
    private static byte[] readCapped(java.io.InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(8192, max));
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max)
                throw new JxException(413, "Body demasiado grande (máx " + max + " bytes)");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ── Variables de atributo de request ─────────────────────────────────

    public void   setVar(String name, Object value) { if (request != null) request.setAttribute(name, value); }
    public Object getVar(String name)               { return request != null ? request.getAttribute(name) : null; }

    // ── Subida de archivos ────────────────────────────────────────────────

    public String lastError() { return lastError; }

    /** Sube un archivo; devuelve la ruta guardada o {@code null} si falla. */
    public Path uploadFile(String field, Path dir) { return uploadFile(field, dir, "*", null); }
    public Path uploadFile(String field, Path dir, String allowedExts) { return uploadFile(field, dir, allowedExts, null); }
    public Path uploadFile(String field, Path dir, String allowedExts, String nameOverride) {
        lastError = null;
        try {
            Files.createDirectories(dir);
            Part part = request.getPart(field);
            if (part == null) { lastError = "Campo no encontrado: " + field; return null; }
            String name = (nameOverride == null || nameOverride.isBlank())
                ? Paths.get(part.getSubmittedFileName()).getFileName().toString()
                : nameOverride.trim();
            if (name.isBlank()) { lastError = "Nombre de archivo vacío"; return null; }
            if (!allowedExt(name, allowedExts)) { lastError = "Extensión no permitida"; return null; }
            Path out = dir.resolve(name).normalize();
            if (!out.startsWith(dir.toAbsolutePath().normalize())) {
                lastError = "Ruta de archivo no permitida";
                return null;
            }
            Files.copy(part.getInputStream(), out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (Exception e) { lastError = e.getMessage(); return null; }
    }

    /** Sube múltiples archivos. */
    public List<Path> uploadFiles(String field, Path dir) { return uploadFiles(field, dir, "*"); }
    public List<Path> uploadFiles(String field, Path dir, String allowedExts) {
        lastError = null;
        List<Path> saved = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            String arr = field.endsWith("[]") ? field : field + "[]";
            for (Part part : request.getParts()) {
                String n = part.getName();
                if (!n.equals(field) && !n.equals(arr)) continue;
                String name = Paths.get(part.getSubmittedFileName()).getFileName().toString();
                if (name.isBlank()) continue;
                if (!allowedExt(name, allowedExts)) { lastError = "Extensión no permitida"; return saved; }
                Path out = dir.resolve(name).normalize();
                if (!out.startsWith(dir.toAbsolutePath().normalize())) {
                    lastError = "Ruta de archivo no permitida"; return saved;
                }
                Files.copy(part.getInputStream(), out, StandardCopyOption.REPLACE_EXISTING);
                saved.add(out);
            }
        } catch (Exception e) { lastError = e.getMessage(); }
        return saved;
    }

    /** Extensiones ejecutables/peligrosas: se rechazan siempre, aunque se permita {@code *}. */
    private static final java.util.Set<String> BLOCKED_EXTS = java.util.Set.of(
            ".jsp", ".jspx", ".jspf", ".jsw", ".jsv", ".class", ".war", ".jar",
            ".sh", ".bat", ".cmd", ".exe", ".dll", ".php", ".phtml", ".pl", ".py", ".rb", ".htaccess");

    private boolean allowedExt(String name, String exts) {
        String lower = name.toLowerCase();
        for (String bad : BLOCKED_EXTS) if (lower.endsWith(bad)) return false;

        if (exts == null || exts.isBlank()) return true;
        String rule = exts.trim().toLowerCase();
        if ("*.*".equals(rule) || "*".equals(rule)) return true;
        for (String part : rule.split(",")) {
            String ext = part.trim();
            if (ext.isEmpty()) continue;
            if (!ext.startsWith(".")) ext = "." + ext;
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ── API PascalCase — primera clase ───────────────────────────────────
    public Path        UploadFile(String f, Path d)              { return uploadFile(f, d); }
    public Path        UploadFile(String f, Path d, String e)    { return uploadFile(f, d, e); }
    public Path        UploadFile(String f,Path d,String e,String n){ return uploadFile(f,d,e,n); }
    public List<Path>  UploadFiles(String f, Path d)             { return uploadFiles(f, d); }
    public List<Path>  UploadFiles(String f, Path d, String e)   { return uploadFiles(f, d, e); }

    HttpServletRequest raw() { return request; }
}
