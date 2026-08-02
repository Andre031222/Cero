/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  improved : R. Andre Vilca Solorzano

package jxmvc.core;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generador automático de especificación OpenAPI 3.0 — cero dependencias externas.
 * Escanea los controladores anotados y construye el JSON del spec.
 *
 * <p>Disponible en {@code GET /jx/openapi}.
 *
 * <p>Configurable en {@code application.properties}:
 * <pre>
 *   jxmvc.openapi.title=Mi API
 *   jxmvc.openapi.version=1.0.0
 *   jxmvc.openapi.description=Descripción de la API
 *   jxmvc.openapi.server=http://localhost:8080/app
 * </pre>
 *
 * <p>Ejemplo de spec generado:
 * <pre>
 * {
 *   "openapi": "3.0.3",
 *   "info": { "title": "Mi API", "version": "1.0.0" },
 *   "paths": {
 *     "/api/users": {
 *       "get": { "summary": "list", "operationId": "UserController_list",
 *                "parameters": [...], "responses": { "200": {...} } }
 *     }
 *   }
 * }
 * </pre>
 */
public final class JxOpenApi {

    private static final JxLogger log = JxLogger.getLogger(JxOpenApi.class);

    private JxOpenApi() {}

    private static volatile String cachedSpec = null;
    private static final AtomicBoolean built  = new AtomicBoolean(false);

    /**
     * Genera (o retorna del caché) la especificación OpenAPI 3.0 como JSON.
     */
    public static String generate() {
        if (built.get() && cachedSpec != null) return cachedSpec;
        synchronized (JxOpenApi.class) {
            if (built.get() && cachedSpec != null) return cachedSpec;
            cachedSpec = build();
            built.set(true);
        }
        return cachedSpec;
    }

    /** Fuerza la regeneración del spec (llamar si los controladores cambian en runtime). */
    public static void invalidate() {
        built.set(false);
        cachedSpec = null;
    }

    // ── Construcción ──────────────────────────────────────────────────────

    private static String build() {
        String title   = BaseDbResolver.property("jxmvc.openapi.title",   "Lux API");
        String version = BaseDbResolver.property("jxmvc.openapi.version", "1.0.0");
        String desc    = BaseDbResolver.property("jxmvc.openapi.description", "Generado por Lux Framework");
        String server  = BaseDbResolver.property("jxmvc.openapi.server",  "http://localhost:8080");

        StringBuilder sb = new StringBuilder();
        sb.append("{\"openapi\":\"3.0.3\"");
        sb.append(",\"info\":{\"title\":\"").append(esc(title)).append("\"")
          .append(",\"version\":\"").append(esc(version)).append("\"")
          .append(",\"description\":\"").append(esc(desc)).append("\"}");
        sb.append(",\"servers\":[{\"url\":\"").append(esc(server)).append("\"}]");
        sb.append(",\"paths\":{");

        Map<String, Map<String, String>> paths = collectPaths();
        boolean firstPath = true;
        for (Map.Entry<String, Map<String, String>> pe : paths.entrySet()) {
            if (!firstPath) sb.append(',');
            sb.append("\"").append(esc(pe.getKey())).append("\":{");
            boolean firstOp = true;
            for (Map.Entry<String, String> oe : pe.getValue().entrySet()) {
                if (!firstOp) sb.append(',');
                sb.append("\"").append(oe.getKey().toLowerCase()).append("\":")
                  .append(oe.getValue());
                firstOp = false;
            }
            sb.append("}");
            firstPath = false;
        }

        sb.append("},\"components\":{\"schemas\":{}}}");
        return sb.toString();
    }

    // ── Escaneo de controladores ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> collectPaths() {
        // TreeMap para orden alfabético
        Map<String, Map<String, String>> paths = new TreeMap<>();

        String pkg = BaseDbResolver.controllerPackage();
        String pkgPath = pkg.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JxOpenApi.class.getClassLoader();

        try {
            Enumeration<java.net.URL> resources = cl.getResources(pkgPath);
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                if (!"file".equalsIgnoreCase(url.getProtocol())) continue;
                java.io.File dir = new java.io.File(
                        java.net.URLDecoder.decode(url.getFile(), java.nio.charset.StandardCharsets.UTF_8));
                if (dir.listFiles() == null) continue;
                for (java.io.File f : dir.listFiles()) {
                    if (!f.getName().endsWith("Controller.class")) continue;
                    String cn = pkg + "." + f.getName().replace(".class", "");
                    try {
                        Class<?> raw = Class.forName(cn);
                        if (!JxController.class.isAssignableFrom(raw)) continue;
                        processController((Class<? extends JxController>) raw, paths);
                    } catch (Exception e) {
                        log.warn("OpenAPI: error procesando controlador {}: {}", cn, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("OpenAPI: error escaneando paquete de controladores: {}", e.getMessage());
        }

        return paths;
    }

    private static void processController(Class<? extends JxController> cls,
                                           Map<String, Map<String, String>> paths) {
        String base = resolveBase(cls);

        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;

            // Detectar verbo y path de la anotación
            String verb  = null;
            String mPath = null;

            JxMapping.JxGetMapping    get    = m.getAnnotation(JxMapping.JxGetMapping.class);
            JxMapping.JxPostMapping   post   = m.getAnnotation(JxMapping.JxPostMapping.class);
            JxMapping.JxPutMapping    put    = m.getAnnotation(JxMapping.JxPutMapping.class);
            JxMapping.JxDeleteMapping delete = m.getAnnotation(JxMapping.JxDeleteMapping.class);
            JxMapping.JxPatchMapping  patch  = m.getAnnotation(JxMapping.JxPatchMapping.class);
            JxMapping.JxAnyMapping    any    = m.getAnnotation(JxMapping.JxAnyMapping.class);

            if      (get    != null) { verb = "GET";    mPath = get.value()[0]; }
            else if (post   != null) { verb = "POST";   mPath = post.value()[0]; }
            else if (put    != null) { verb = "PUT";    mPath = put.value()[0]; }
            else if (delete != null) { verb = "DELETE"; mPath = delete.value()[0]; }
            else if (patch  != null) { verb = "PATCH";  mPath = patch.value()[0]; }
            else if (any    != null) { verb = "GET";    mPath = any.value()[0]; }
            else continue;

            String fullPath = buildPath(base, m.getName(), mPath);
            // Convertir {var} → OpenAPI {var} (ya es compatible)
            String oaPath = fullPath;

            paths.computeIfAbsent(oaPath, k -> new TreeMap<>())
                 .put(verb, buildOperation(cls, m, verb));
        }
    }

    private static String buildOperation(Class<?> cls, Method m, String verb) {
        StringBuilder op = new StringBuilder("{");
        op.append("\"summary\":\"").append(esc(m.getName())).append("\"");
        op.append(",\"operationId\":\"")
          .append(esc(cls.getSimpleName() + "_" + m.getName())).append("\"");

        // Tags — nombre del controlador sin "Controller"
        String tag = cls.getSimpleName().replace("Controller", "");
        op.append(",\"tags\":[\"").append(esc(tag)).append("\"]");

        // @JxAuth
        if (m.isAnnotationPresent(JxMapping.JxAuth.class) ||
            cls.isAnnotationPresent(JxMapping.JxAuth.class)) {
            op.append(",\"security\":[{\"bearerAuth\":[]}]");
        }

        // Parámetros
        op.append(",\"parameters\":[");
        Parameter[] params = m.getParameters();
        boolean firstParam = true;
        for (Parameter p : params) {
            JxMapping.JxPathVar pv = p.getAnnotation(JxMapping.JxPathVar.class);
            JxMapping.JxParam   qp = p.getAnnotation(JxMapping.JxParam.class);
            JxMapping.JxRequestHeader rh = p.getAnnotation(JxMapping.JxRequestHeader.class);

            if (pv != null) {
                if (!firstParam) op.append(',');
                String name = pv.value().isBlank() ? p.getName() : pv.value();
                op.append(paramJson(name, "path", true, p.getType()));
                firstParam = false;
            } else if (qp != null) {
                if (!firstParam) op.append(',');
                String name = qp.value().isBlank() ? p.getName() : qp.value();
                op.append(paramJson(name, "query", qp.required(), p.getType()));
                firstParam = false;
            } else if (rh != null) {
                if (!firstParam) op.append(',');
                op.append(paramJson(rh.value(), "header", rh.required(), p.getType()));
                firstParam = false;
            }
        }
        op.append("]");

        // Request body
        boolean hasBody = Arrays.stream(params)
                .anyMatch(p -> p.isAnnotationPresent(JxMapping.JxBody.class));
        if (hasBody && ("POST".equals(verb) || "PUT".equals(verb) || "PATCH".equals(verb))) {
            op.append(",\"requestBody\":{\"required\":true,\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\"}}}}");
        }

        // Respuestas
        op.append(",\"responses\":{");
        JxMapping.JxResponseStatus rs = m.getAnnotation(JxMapping.JxResponseStatus.class);
        int successCode = rs != null ? rs.value() : ("POST".equals(verb) ? 201 : 200);
        op.append("\"").append(successCode).append("\":{\"description\":\"OK\"}");
        if (m.isAnnotationPresent(JxMapping.JxAuth.class) || cls.isAnnotationPresent(JxMapping.JxAuth.class)) {
            op.append(",\"401\":{\"description\":\"No autorizado\"}");
        }
        op.append("}}");

        return op.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String resolveBase(Class<? extends JxController> cls) {
        JxMapping.JxControllerMapping cm = cls.getAnnotation(JxMapping.JxControllerMapping.class);
        if (cm != null && !cm.value().isBlank()) return "/" + cm.value().trim().replaceAll("^/", "");
        JxMapping.JxRestController rc = cls.getAnnotation(JxMapping.JxRestController.class);
        if (rc != null && !rc.value().isBlank()) return "/" + rc.value().trim().replaceAll("^/", "");
        String s = cls.getSimpleName().replace("Controller", "").toLowerCase();
        return "/" + s;
    }

    private static String buildPath(String base, String methodName, String value) {
        if (value == null || value.isBlank()) return (base + "/" + methodName).toLowerCase();
        if (value.startsWith("/")) return value;
        return (base + "/" + value).toLowerCase();
    }

    private static String paramJson(String name, String in, boolean required, Class<?> type) {
        String schema = typeSchema(type);
        return "{\"name\":\"" + esc(name) + "\""
                + ",\"in\":\"" + in + "\""
                + ",\"required\":" + required
                + ",\"schema\":" + schema + "}";
    }

    private static String typeSchema(Class<?> type) {
        if (type == String.class)                              return "{\"type\":\"string\"}";
        if (type == int.class || type == Integer.class)        return "{\"type\":\"integer\",\"format\":\"int32\"}";
        if (type == long.class || type == Long.class)          return "{\"type\":\"integer\",\"format\":\"int64\"}";
        if (type == double.class || type == Double.class)      return "{\"type\":\"number\",\"format\":\"double\"}";
        if (type == float.class || type == Float.class)        return "{\"type\":\"number\",\"format\":\"float\"}";
        if (type == boolean.class || type == Boolean.class)    return "{\"type\":\"boolean\"}";
        return "{\"type\":\"object\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
