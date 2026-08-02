/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contenedor de servicios (DI simple) — singletons sin XML ni bytecode magic.
 *
 * <h3>Características</h3>
 * <ul>
 *   <li>Escaneo automático del paquete de servicios al primer uso.</li>
 *   <li>Inyección de campos {@code @JxInject} y {@code @JxValue}.</li>
 *   <li>Scope {@code SINGLETON} (defecto) y {@code PROTOTYPE} vía {@code @JxScope}.</li>
 *   <li>Inyección por constructor con {@code @JxInject} en el constructor.</li>
 *   <li>Binding automático de {@code @JxConfigProperties}.</li>
 *   <li>Detección de dependencias circulares.</li>
 *   <li>Inyección de {@code @JxDS} en campos {@code JxDB}.</li>
 *   <li>Registro del backend de caché vía {@code @JxCacheManager}.</li>
 * </ul>
 *
 * <pre>
 *   &#64;JxService
 *   public class UserService {
 *       &#64;JxInject private JxDB db;
 *
 *       public DBRowSet findAll() { return db.getTable("users"); }
 *   }
 *
 *   &#64;JxControllerMapping("users")
 *   public class UserController extends JxController {
 *       &#64;JxInject private UserService userService;
 *
 *       &#64;JxGetMapping("list")
 *       public ActionResult list() { return json(userService.findAll()); }
 *   }
 * </pre>
 *
 * Registro manual:
 * <pre>
 *   JxServiceRegistry.register(new UserService());
 *   JxServiceRegistry.register(MyConfig.class, new MyConfig("prod"));
 * </pre>
 */
public final class JxServiceRegistry {

    private static final JxLogger LOG = JxLogger.getLogger(JxServiceRegistry.class);

    private static final Map<Class<?>, Object>  singletons = new ConcurrentHashMap<>();
    private static final Set<Class<?>>          prototypes = ConcurrentHashMap.newKeySet();
    private static volatile boolean             scanned    = false;

    /** Detecta ciclos: clases que están siendo inicializadas en el hilo actual. */
    private static final ThreadLocal<Set<Class<?>>> creating =
            ThreadLocal.withInitial(LinkedHashSet::new);

    private JxServiceRegistry() {}

    // ── Registro manual ───────────────────────────────────────────────────

    /** Registra un singleton manualmente. */
    public static void register(Object instance) {
        singletons.put(instance.getClass(), instance);
    }

    /** Registra bajo una clase/interfaz específica. */
    public static void register(Class<?> type, Object instance) {
        singletons.put(type, instance);
    }

    /** Devuelve todos los singletons registrados. */
    public static Collection<Object> values() {
        ensureScanned();
        return Set.copyOf(singletons.values());
    }

    /** Obtiene un singleton por tipo, o crea nueva instancia si es PROTOTYPE. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> type) {
        ensureScanned();

        // Prototype: crear nueva instancia
        if (prototypes.contains(type)) {
            return createInstance(type);
        }

        Object found = singletons.get(type);
        if (found != null) return (T) found;
        // Buscar por compatibilidad (subclases / interfaces)
        for (Map.Entry<Class<?>, Object> e : singletons.entrySet()) {
            if (type.isAssignableFrom(e.getKey())) return (T) e.getValue();
        }
        return null;
    }

    // ── Inyección ─────────────────────────────────────────────────────────

    /**
     * Inyecta los campos {@code @JxInject}, {@code @JxValue} y {@code @JxDS} en el objeto dado.
     * Se llama automáticamente para controladores y servicios.
     */
    public static void inject(Object target) {
        if (target == null) return;
        ensureScanned();
        injectFields(target, target.getClass());
    }

    private static void injectFields(Object target, Class<?> cls) {
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                field.setAccessible(true);

                // @JxInject — inyección de servicio
                if (field.getAnnotation(JxMapping.JxInject.class) != null) {
                    Object dep = resolveField(field, target.getClass());
                    if (dep != null) {
                        try { field.set(target, dep); }
                        catch (IllegalAccessException e) {
                            LOG.warn("@JxInject falló en {}.{}: {}",
                                    cls.getSimpleName(), field.getName(), e.getMessage());
                        }
                    }
                    continue;
                }

                // @JxValue — inyección de propiedad
                JxMapping.JxValue jv = field.getAnnotation(JxMapping.JxValue.class);
                if (jv != null) {
                    String resolved = resolveValueExpr(jv.value());
                    try { field.set(target, coerceValue(resolved, field.getType())); }
                    catch (IllegalAccessException e) {
                        LOG.warn("@JxValue falló en {}.{}: {}",
                                cls.getSimpleName(), field.getName(), e.getMessage());
                    }
                }
            }
            cls = cls.getSuperclass();
        }
    }

    // ── Resolución de @JxValue ────────────────────────────────────────────

    private static String resolveValueExpr(String expr) {
        if (expr == null) return "";
        if (expr.startsWith("${") && expr.endsWith("}")) {
            String inner = expr.substring(2, expr.length() - 1);
            int colon = inner.indexOf(':');
            String key = colon >= 0 ? inner.substring(0, colon).trim() : inner.trim();
            String def = colon >= 0 ? inner.substring(colon + 1) : "";
            return BaseDbResolver.property(key, def);
        }
        return expr;  // valor literal
    }

    // BUG FIX #2: type coercion ya no es muda — avisa cuando el valor es inválido
    private static Object coerceValue(String val, Class<?> type) {
        if (type == String.class) return val;
        if (type == int.class || type == Integer.class) {
            try { return Integer.parseInt(val.trim()); }
            catch (Exception e) { LOG.warn("@JxValue: '{}' no es un int válido, usando 0", val); return 0; }
        }
        if (type == long.class || type == Long.class) {
            try { return Long.parseLong(val.trim()); }
            catch (Exception e) { LOG.warn("@JxValue: '{}' no es un long válido, usando 0", val); return 0L; }
        }
        if (type == double.class || type == Double.class) {
            try { return Double.parseDouble(val.trim()); }
            catch (Exception e) { LOG.warn("@JxValue: '{}' no es un double válido, usando 0.0", val); return 0.0; }
        }
        if (type == float.class || type == Float.class) {
            try { return Float.parseFloat(val.trim()); }
            catch (Exception e) { LOG.warn("@JxValue: '{}' no es un float válido, usando 0.0", val); return 0f; }
        }
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(val.trim());
        return val;
    }

    // ── Escaneo automático ────────────────────────────────────────────────

    private static void ensureScanned() {
        if (scanned) return;
        synchronized (JxServiceRegistry.class) {
            if (scanned) return;
            scanServices();
            scanned = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanServices() {
        String pkg = BaseDbResolver.servicesPackage();
        String pkgPath = pkg.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JxServiceRegistry.class.getClassLoader();

        try {
            var resources = cl.getResources(pkgPath);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                if (!"file".equalsIgnoreCase(url.getProtocol())) continue;
                var dir = new java.io.File(java.net.URLDecoder.decode(url.getFile(),
                        java.nio.charset.StandardCharsets.UTF_8));
                if (dir.listFiles() == null) continue;
                for (var f : dir.listFiles()) {
                    if (!f.getName().endsWith(".class")) continue;
                    String cn = pkg + "." + f.getName().replace(".class", "");
                    try {
                        Class<?> cls = Class.forName(cn);
                        if (!cls.isAnnotationPresent(JxMapping.JxService.class)) continue;
                        if (singletons.containsKey(cls)) continue;

                        // @JxScope(PROTOTYPE) — no instanciar aún, solo registrar
                        JxMapping.JxScope scope = cls.getAnnotation(JxMapping.JxScope.class);
                        if (scope != null && scope.value() == JxMapping.ScopeType.PROTOTYPE) {
                            prototypes.add(cls);
                            LOG.debug("Servicio PROTOTYPE registrado: {}", cls.getSimpleName());
                            continue;
                        }

                        Object inst = instantiate(cls);
                        singletons.put(cls, inst);
                        LOG.debug("Servicio singleton registrado: {}", cls.getSimpleName());
                    } catch (JxException je) {
                        LOG.warn("No se pudo registrar servicio {}: {}", cn, je.getMessage());
                    } catch (Exception e) {
                        LOG.warn("Error cargando clase de servicio {}: {}", cn, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error escaneando paquete de servicios '{}': {}", pkg, e.getMessage());
        }

        // Segunda pasada: inyectar dependencias
        for (Object service : Set.copyOf(singletons.values())) {
            inject(service);
        }

        // Tercera pasada: registrar event listeners + cache managers
        for (Object service : Set.copyOf(singletons.values())) {
            JxEventBus.registerListeners(service);
            // @JxCacheManager — backend de caché pluggable
            if (service.getClass().isAnnotationPresent(JxMapping.JxCacheManager.class)
                    && service instanceof JxCacheBackend backend) {
                JxCache.setGlobalBackend(backend);
            }
        }
    }

    // ── Instanciación (constructor injection + @JxConfigProperties) ───────

    /**
     * Crea una instancia de la clase usando:
     * 1. Constructor con {@code @JxInject} (constructor injection)
     * 2. {@code @JxConfigProperties} si está presente (binding de propiedades)
     * 3. Constructor por defecto
     *
     * Detecta dependencias circulares mediante un {@link ThreadLocal}.
     */
    static Object instantiate(Class<?> cls) {
        Set<Class<?>> inProgress = creating.get();
        if (inProgress.contains(cls)) {
            throw new JxException(500, "Dependencia circular detectada al crear: " + cls.getName()
                    + " → " + inProgress);
        }
        inProgress.add(cls);
        try {
            // @JxConfigProperties: usar JxConfigBinder
            if (cls.isAnnotationPresent(JxMapping.JxConfigProperties.class)) {
                return JxConfigBinder.bind(cls);
            }

            // Constructor con @JxInject
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                if (ctor.getAnnotation(JxMapping.JxInject.class) != null) {
                    return injectConstructor(ctor);
                }
            }

            // Constructor por defecto
            Constructor<?> def = cls.getDeclaredConstructor();
            def.setAccessible(true);
            return def.newInstance();

        } catch (JxException e) {
            throw e;
        } catch (Exception e) {
            throw new JxException(500, "Error al instanciar " + cls.getName() + ": " + e.getMessage());
        } finally {
            inProgress.remove(cls);
        }
    }

    // BUG FIX #3: constructor injection valida que todos los params puedan resolverse
    private static Object injectConstructor(Constructor<?> ctor) throws Exception {
        ctor.setAccessible(true);
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> pType = params[i].getType();
            args[i] = get(pType);

            if (args[i] == null && !pType.isPrimitive()) {
                // Intentar crear si es un servicio conocido
                if (pType.isAnnotationPresent(JxMapping.JxService.class)) {
                    args[i] = instantiate(pType);
                }
                // Advertir si sigue siendo null — no pasar null silenciosamente
                if (args[i] == null) {
                    LOG.warn("Constructor injection en {}: parámetro {} ({}) no pudo resolverse — se pasará null",
                            ctor.getDeclaringClass().getSimpleName(), i, pType.getSimpleName());
                }
            }
        }
        return ctor.newInstance(args);
    }

    // ── Creación de instancias prototype ─────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> T createInstance(Class<T> cls) {
        try {
            Object inst = instantiate(cls);
            injectFields(inst, cls);
            return (T) inst;
        } catch (Exception e) {
            throw new JxException(500, "Error creando instancia prototype de " + cls.getName());
        }
    }

    // ── Resolución de campo @JxInject ─────────────────────────────────────

    private static Object resolveField(Field field, Class<?> ownerClass) {
        Class<?> type = field.getType();

        // JxDB: verificar si el owner tiene @JxDS para usar datasource secundario
        if (JxDB.class.isAssignableFrom(type)) {
            // @JxDS en el campo
            JxMapping.JxDS fieldDs = field.getAnnotation(JxMapping.JxDS.class);
            if (fieldDs != null) return new JxDB(fieldDs.value());
            // @JxDS en la clase owner
            JxMapping.JxDS classDs = ownerClass.getAnnotation(JxMapping.JxDS.class);
            if (classDs != null) return new JxDB(classDs.value());
            return new JxDB();
        }

        // Prototype
        if (prototypes.contains(type)) return createInstance(type);

        // Buscar en el registro
        return get(type);
    }
}
