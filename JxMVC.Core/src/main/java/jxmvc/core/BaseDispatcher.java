/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motor de enrutamiento de JxMVC.
 * Resuelve la ruta HTTP al controlador y acción correspondientes
 * usando anotaciones o la convención {@code /controlador/accion/arg0/arg1}.
 *
 * El paquete de controladores se configura en {@code application.properties}:
 * <pre>
 *   jxmvc.controllers.package=com.miapp.controllers
 * </pre>
 */
class BaseDispatcher {

    private static final JxLogger  log            = JxLogger.getLogger(BaseDispatcher.class);
    private static final String CONTROLLER_PKG = BaseDbResolver.controllerPackage();
    private static final String[] ALL_VERBS    = {"GET","POST","PUT","DELETE","PATCH"};

    private final Map<String, Constructor<? extends JxController>> constructorCache = new ConcurrentHashMap<>();
    private final Map<String, AnnotatedRoute>                      routeCache       = new ConcurrentHashMap<>();
    private final List<TemplateRoute>                              templateRoutes   = new CopyOnWriteArrayList<>();
    private volatile boolean indexed;

    // ── Resolución de ruta ────────────────────────────────────────────────

    BaseDispatchPlan resolve(HttpServletRequest request) throws Exception {
        String rawPath  = extractPath(request);
        String path     = normalize(rawPath);
        if ("/__invalid".equals(path)) throw new JxException(400, "Ruta inválida");
        String verb     = request.getMethod().toUpperCase();
        // HEAD se resuelve como GET: el contenedor omite el cuerpo en la respuesta.
        if ("HEAD".equals(verb)) verb = "GET";

        AnnotatedMatch match = resolveAnnotated(verb, path, rawPath);
        if (match == null) {
            if ("/".equals(path)) {
                BaseDispatchPlan main = resolveMain(verb);
                if (main != null) return main;
            }
            return resolveConventional(rawPath, verb);
        }

        AnnotatedRoute route = match.route();
        return new BaseDispatchPlan(
                token(route.cls()), route.method().getName().toLowerCase(),
                match.args(), match.pathVars(), route.cls(), route.method());
    }

    // ── Instanciación ─────────────────────────────────────────────────────

    JxController newInstance(Class<? extends JxController> cls) throws ReflectiveOperationException {
        Constructor<? extends JxController> ctor = constructorCache.computeIfAbsent(cls.getName(), k -> {
            try {
                Constructor<? extends JxController> c = cls.getDeclaredConstructor();
                c.setAccessible(true);
                return c;
            } catch (NoSuchMethodException ex) { throw new IllegalStateException(ex); }
        });
        return ctor.newInstance();
    }

    // ── Ruta convencional ─────────────────────────────────────────────────

    private BaseDispatchPlan resolveConventional(String rawPath, String verb) throws Exception {
        String[] seg = rawPath.equals("/") ? new String[0]
                : Arrays.stream(rawPath.split("/")).filter(s -> !s.isBlank()).toArray(String[]::new);

        String ctrl   = seg.length > 0 ? token(seg[0], "home") : "home";
        String action = seg.length > 1 ? token(seg[1], "index") : "index";
        String[] args = seg.length > 2 ? Arrays.copyOfRange(seg, 2, seg.length) : new String[0];

        Class<? extends JxController> cls    = resolveClass(ctrl);
        Method                        method = resolveMethod(cls, action, verb);
        return new BaseDispatchPlan(ctrl, action, args, cls, method);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends JxController> resolveClass(String ctrl) throws ClassNotFoundException {
        String name = CONTROLLER_PKG + "." + cap(token(ctrl, "")) + "Controller";
        Class<?> raw = Class.forName(name);
        if (!JxController.class.isAssignableFrom(raw))
            throw new ClassNotFoundException("No es un JxController: " + name);
        return (Class<? extends JxController>) raw;
    }

    private Method resolveMethod(Class<? extends JxController> cls, String action, String verb) throws Exception {
        for (Method m : cls.getDeclaredMethods()) {
            if (!isAction(m) || !m.getName().equalsIgnoreCase(action)) continue;
            if (!acceptsVerb(m, verb)) {
                log.debug("405: {} no permitido en {}#{}", verb, cls.getSimpleName(), action);
                throw new JxException(405, "Método HTTP no permitido: " + verb);
            }
            return m;
        }
        throw new NoSuchMethodException(cls.getName() + "#" + action);
    }

    // ── Ruta raíz (@JxControllerMain) ────────────────────────────────────

    private BaseDispatchPlan resolveMain(String verb) {
        for (Class<? extends JxController> cls : scan()) {
            if (cls.getAnnotation(JxMapping.JxControllerMain.class) == null) continue;
            for (Method m : cls.getDeclaredMethods()) {
                if (isAction(m) && "index".equalsIgnoreCase(m.getName()) && acceptsVerb(m, verb)) {
                    return new BaseDispatchPlan(token(cls), "index", new String[0], cls, m);
                }
            }
        }
        return null;
    }

    // ── Índice de rutas anotadas ──────────────────────────────────────────

    private AnnotatedMatch resolveAnnotated(String verb, String path, String rawPath) {
        return resolveAnnotated(verb, path, rawPath, true);
    }

    private AnnotatedMatch resolveAnnotated(String verb, String path, String rawPath, boolean check405) {
        ensureIndexed();
        String prefix = verb + ":";

        // 1. Coincidencia exacta
        AnnotatedRoute exact = routeCache.get(prefix + path);
        if (exact != null) return new AnnotatedMatch(exact, new String[0], Collections.emptyMap());

        // 2. 405: misma ruta, otro verbo (omitido al resolver un preflight OPTIONS)
        if (check405 && !"OPTIONS".equals(verb)) {
            for (String v : ALL_VERBS) {
                if (v.equals(verb)) continue;
                if (routeCache.containsKey(v + ":" + path))
                    throw new JxException(405, "Método HTTP no permitido: " + verb);
            }
        }

        // 3. Coincidencia por prefijo (args en la ruta)
        // Solo aplica a rutas con segmento de acción explícito (>=2 segmentos: /ctrl/action)
        // para evitar que la ruta base /ctrl capture /ctrl/desconocido como argumento.
        AnnotatedRoute best     = null;
        String         bestPath = null;
        for (Map.Entry<String, AnnotatedRoute> e : routeCache.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String rp = e.getKey().substring(prefix.length());
            // Requiere al menos /ctrl/action (un '/' interno después del primero)
            if (rp.indexOf('/', 1) < 0) continue;
            if (path.startsWith(rp + "/") && (bestPath == null || rp.length() > bestPath.length())) {
                bestPath = rp;
                best     = e.getValue();
            }
        }

        if (best != null) return new AnnotatedMatch(best, extraArgs(rawPath, bestPath), Collections.emptyMap());

        // 4. Coincidencia por plantilla ({variable})
        for (TemplateRoute tr : templateRoutes) {
            if (!tr.verb().equals(verb) && !"ANY".equals(tr.verb())) continue;
            if (!tr.pattern().matcher(path).matches()) continue;
            Matcher rawM = tr.ciPattern().matcher(rawPath);
            Map<String, String> vars = new LinkedHashMap<>();
            if (rawM.matches()) {
                for (int i = 0; i < tr.varNames().size(); i++)
                    vars.put(tr.varNames().get(i).toLowerCase(),
                             URLDecoder.decode(rawM.group(i + 1), StandardCharsets.UTF_8));
            } else {
                Matcher m = tr.pattern().matcher(path);
                m.matches();
                for (int i = 0; i < tr.varNames().size(); i++)
                    vars.put(tr.varNames().get(i).toLowerCase(),
                             URLDecoder.decode(m.group(i + 1), StandardCharsets.UTF_8));
            }
            return new AnnotatedMatch(tr.route(), new String[0], vars);
        }

        // 5. 405 también para plantillas: la ruta existe con otro verbo
        if (check405 && !"OPTIONS".equals(verb)) {
            for (TemplateRoute tr : templateRoutes) {
                if (tr.verb().equals(verb) || "ANY".equals(tr.verb())) continue;
                if (tr.pattern().matcher(path).matches())
                    throw new JxException(405, "Método HTTP no permitido: " + verb);
            }
        }

        // 6. OPTIONS: buscar en todos los verbos sin disparar el 405 del preflight
        if ("OPTIONS".equals(verb)) {
            for (String v : ALL_VERBS) {
                AnnotatedMatch m = resolveAnnotated(v, path, rawPath, false);
                if (m != null) return m;
            }
        }
        return null;
    }

    private void ensureIndexed() {
        if (indexed) return;
        synchronized (this) {
            if (indexed) return;
            buildIndex();
            indexed = true;
        }
    }

    private void buildIndex() {
        for (Class<? extends JxController> cls : scan()) {
            String  base   = basePath(cls);
            boolean isMain = cls.getAnnotation(JxMapping.JxControllerMain.class) != null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!isAction(m)) continue;
                registerAnnotation("GET",    m.getAnnotation(JxMapping.JxGetMapping.class),    base, m, cls, isMain);
                registerAnnotation("POST",   m.getAnnotation(JxMapping.JxPostMapping.class),   base, m, cls, isMain);
                registerAnnotation("PUT",    m.getAnnotation(JxMapping.JxPutMapping.class),    base, m, cls, isMain);
                registerAnnotation("DELETE", m.getAnnotation(JxMapping.JxDeleteMapping.class), base, m, cls, isMain);
                registerAnnotation("PATCH",  m.getAnnotation(JxMapping.JxPatchMapping.class),  base, m, cls, isMain);
                JxMapping.JxAnyMapping any = m.getAnnotation(JxMapping.JxAnyMapping.class);
                if (any != null) {
                    for (String val : any.value())
                        for (String v : ALL_VERBS) registerRoute(v, val, base, m, cls, isMain);
                }
            }
        }
        // Orden determinista de plantillas: el escaneo viene de un HashSet sin orden.
        // Gana la más específica (menos variables; a igualdad, patrón más largo).
        templateRoutes.sort(Comparator
                .comparingInt((TemplateRoute t) -> t.varNames().size())
                .thenComparing(t -> -t.pattern().pattern().length())
                .thenComparing(t -> t.pattern().pattern()));
    }

    private boolean hasTemplateVars(String path) {
        return path.contains("{") && path.contains("}");
    }

    private void addTemplateRoute(String verb, String rp, Method m, Class<? extends JxController> cls) {
        List<String> varNames = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([^/}]+)}").matcher(rp);
        String regex = matcher.replaceAll(result -> {
            varNames.add(result.group(1).trim());
            return "([^/]+)";
        });
        String full = "^" + regex + "$";
        templateRoutes.add(new TemplateRoute(verb,
                Pattern.compile(full),
                Pattern.compile(full, Pattern.CASE_INSENSITIVE),
                varNames, new AnnotatedRoute(cls, m)));
    }

    private void registerAnnotation(String verb, Object ann, String base,
                                    Method m, Class<? extends JxController> cls, boolean isMain) {
        if (ann == null) return;
        String[] values = ann instanceof JxMapping.JxGetMapping    g ? g.value()
                        : ann instanceof JxMapping.JxPostMapping   p ? p.value()
                        : ann instanceof JxMapping.JxPutMapping    u ? u.value()
                        : ann instanceof JxMapping.JxDeleteMapping d ? d.value()
                        :                                            ((JxMapping.JxPatchMapping) ann).value();
        for (String value : values) registerRoute(verb, value, base, m, cls, isMain);
    }

    private void registerRoute(String verb, String value, String base,
                                Method m, Class<? extends JxController> cls, boolean isMain) {
        String rp = methodPath(base, m.getName(), value);
        if (hasTemplateVars(rp)) {
            addTemplateRoute(verb, rp, m, cls);
            return;
        }
        AnnotatedRoute prev = routeCache.putIfAbsent(verb + ":" + rp, new AnnotatedRoute(cls, m));
        if (prev != null && (prev.cls() != cls || !prev.method().equals(m))) {
            log.warn("Ruta duplicada {} {} — la sirve {}#{}; se ignora {}#{}",
                    verb, rp, prev.cls().getSimpleName(), prev.method().getName(),
                    cls.getSimpleName(), m.getName());
        }

        if ("index".equalsIgnoreCase(m.getName()) && rp.equals(base + "/index")) {
            routeCache.putIfAbsent(verb + ":" + base, new AnnotatedRoute(cls, m));
        }
        if (isMain && "index".equalsIgnoreCase(m.getName())) {
            routeCache.putIfAbsent(verb + ":/", new AnnotatedRoute(cls, m));
        }
    }

    // ── Escaneo de controladores ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Set<Class<? extends JxController>> scan() {
        Set<Class<? extends JxController>> classes = new HashSet<>();
        String pkg = CONTROLLER_PKG.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JxController.class.getClassLoader();

        try {
            Enumeration<URL> resources = cl.getResources(pkg);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("file".equalsIgnoreCase(url.getProtocol())) {
                    File dir = new File(URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8));
                    if (dir.listFiles() == null) continue;
                    for (File f : dir.listFiles()) {
                        if (!f.getName().endsWith("Controller.class")) continue;
                        String cn = CONTROLLER_PKG + "." + f.getName().replace(".class", "");
                        addClass(cn, classes);
                    }
                } else if ("jar".equalsIgnoreCase(url.getProtocol())) {
                    try (JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry e = entries.nextElement();
                            if (e.isDirectory() || !e.getName().startsWith(pkg) || !e.getName().endsWith("Controller.class")) continue;
                            addClass(e.getName().replace('/', '.').replace(".class", ""), classes);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error escaneando controladores en classpath: {}", e.getMessage());
        }
        return classes;
    }

    @SuppressWarnings("unchecked")
    private void addClass(String name, Set<Class<? extends JxController>> set) {
        try {
            Class<?> raw = Class.forName(name);
            if (JxController.class.isAssignableFrom(raw)) set.add((Class<? extends JxController>) raw);
        } catch (ClassNotFoundException ignored) {}  // clase no disponible en classpath actual
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    private boolean isAction(Method m) {
        return Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers());
    }

    private boolean acceptsVerb(Method m, String verb) {
        boolean get    = m.isAnnotationPresent(JxMapping.JxGetMapping.class);
        boolean post   = m.isAnnotationPresent(JxMapping.JxPostMapping.class);
        boolean put    = m.isAnnotationPresent(JxMapping.JxPutMapping.class);
        boolean delete = m.isAnnotationPresent(JxMapping.JxDeleteMapping.class);
        boolean patch  = m.isAnnotationPresent(JxMapping.JxPatchMapping.class);
        boolean any    = m.isAnnotationPresent(JxMapping.JxAnyMapping.class);

        if (any) return true;
        if (!get && !post && !put && !delete && !patch) return true; // sin anotación = todos

        return switch (verb) {
            case "GET", "HEAD" -> get;
            case "POST"    -> post;
            case "PUT"     -> put;
            case "DELETE"  -> delete;
            case "PATCH"   -> patch;
            case "OPTIONS" -> get || post || put || delete || patch;
            default        -> false;
        };
    }

    private String basePath(Class<? extends JxController> cls) {
        JxMapping.JxControllerMapping cm = cls.getAnnotation(JxMapping.JxControllerMapping.class);
        if (cm != null && !cm.value().isBlank()) {
            String v = cm.value().trim();
            return normalize(v.startsWith("/") ? v : "/" + v);
        }
        // @JxRestController también puede definir el prefijo de ruta
        JxMapping.JxRestController rc = cls.getAnnotation(JxMapping.JxRestController.class);
        if (rc != null && !rc.value().isBlank()) {
            String v = rc.value().trim();
            return normalize(v.startsWith("/") ? v : "/" + v);
        }
        return normalize("/" + token(cls));
    }

    private String methodPath(String base, String name, String value) {
        if (value == null) return normalize(base + "/" + name);
        String v = value.trim();
        if (v.isEmpty()) return normalize(base);
        return v.startsWith("/") ? normalize(v) : normalize(base + "/" + v);
    }

    private String extractPath(HttpServletRequest req) {
        String uri  = req.getRequestURI();
        String ctx  = req.getContextPath();
        if (uri.startsWith(ctx)) uri = uri.substring(ctx.length());
        return uri.isBlank() ? "/" : uri;
    }

    private String normalize(String path) {
        if (path == null || path.isBlank()) return "/";
        String n = path.trim();
        if (!n.startsWith("/")) n = "/" + n;
        n = n.replaceAll("/+", "/");
        if (n.length() > 1 && n.endsWith("/")) n = n.substring(0, n.length() - 1);
        if (n.contains("..") || n.contains("\\")) return "/__invalid";
        return n.toLowerCase();
    }

    private String token(Class<? extends JxController> cls) {
        String s = cls.getSimpleName();
        return token(s.endsWith("Controller") ? s.substring(0, s.length() - "Controller".length()) : s, "");
    }

    private String token(String val, String fallback) {
        if (val == null || val.isBlank()) return fallback;
        String s = val.replaceAll("[^a-zA-Z0-9_]", "").toLowerCase();
        return s.isBlank() ? fallback : s;
    }

    private String cap(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String[] extraArgs(String rawPath, String matched) {
        String norm = normalize(rawPath);
        if (!norm.startsWith(matched + "/")) return new String[0];
        // Por segmentos (no por offset): el path crudo puede traer // o mayúsculas
        // que desalinean los índices respecto al normalizado.
        String[] rawSegs = Arrays.stream(rawPath.split("/"))
                .filter(s -> !s.isBlank()).toArray(String[]::new);
        int matchedSegs = (int) Arrays.stream(matched.split("/"))
                .filter(s -> !s.isBlank()).count();
        if (rawSegs.length <= matchedSegs) return new String[0];
        String[] out = new String[rawSegs.length - matchedSegs];
        for (int i = 0; i < out.length; i++)
            out[i] = URLDecoder.decode(rawSegs[matchedSegs + i], StandardCharsets.UTF_8);
        return out;
    }

    private record AnnotatedRoute(Class<? extends JxController> cls, Method method) {}
    private record AnnotatedMatch(AnnotatedRoute route, String[] args, Map<String, String> pathVars) {}
    private record TemplateRoute(String verb, Pattern pattern, Pattern ciPattern, List<String> varNames, AnnotatedRoute route) {}
}
