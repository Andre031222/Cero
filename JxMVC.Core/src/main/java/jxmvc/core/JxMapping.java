/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.lang.annotation.*;

/**
 * Anotaciones de enrutamiento y seguridad de JxMVC.
 *
 * <pre>
 *   &#64;JxControllerMapping("api/users")
 *   public class UserController extends JxController {
 *
 *       &#64;JxGetMapping("list")
 *       public ActionResult list() { ... }       // GET /api/users/list
 *
 *       &#64;JxPostMapping("save")
 *       public ActionResult save() { ... }       // POST /api/users/save
 *
 *       &#64;JxPutMapping("{id}")
 *       public ActionResult update(int id) { ... }  // PUT /api/users/{id}
 *
 *       &#64;JxDeleteMapping("{id}")
 *       public ActionResult delete(int id) { ... }  // DELETE /api/users/{id}
 *   }
 * </pre>
 */
public final class JxMapping {

    private JxMapping() {}

    // ── Mapeo de controlador ─────────────────────────────────────────────

    /** Prefijo base de todas las rutas del controlador. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxControllerMapping {
        String value() default "";
        boolean strict() default false;
    }

    /** Marca el controlador que responde a la raíz "/". */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxControllerMain {}

    // ── Mapeo de métodos HTTP ────────────────────────────────────────────

    /**
     * GET — acepta una o varias rutas: {@code @JxGetMapping({"/about","about"})}
     * registra el metodo en ambas rutas simultaneamente.
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxGetMapping    { String[] value() default {""}; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxPostMapping   { String[] value() default {""}; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxPutMapping    { String[] value() default {""}; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxDeleteMapping { String[] value() default {""}; }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxPatchMapping  { String[] value() default {""}; }

    /** Acepta cualquier verbo HTTP — tambien soporta multiples rutas. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxAnyMapping    { String[] value() default {""}; }

    // ── CORS ─────────────────────────────────────────────────────────────

    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface JxCors {
        String[] origins()         default {};
        String[] hosts()           default {};
        String[] methods()         default {};
        boolean allowCredentials() default false;
        long maxAge()              default 3600L;
    }

    // ── Autenticación ────────────────────────────────────────────────────

    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface JxAuth {
        String[] roles()   default {};
        boolean required() default true;
    }

    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface JxRequireAuth {}

    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface JxRequireRole { String[] value(); }

    /**
     * Excluye la acción (o todo el controlador) de la validación CSRF cuando
     * {@code jxmvc.security.csrf=true} — típico en APIs consumidas sin sesión.
     */
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface JxCsrfExempt {}

    // ── Parámetros de método ─────────────────────────────────────────────

    /** Vincula un segmento de ruta {@code {variable}} al parámetro del método. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface JxPathVar {
        /** Nombre de la variable en la plantilla, e.g. {@code "id"}. Vacío = nombre del parámetro. */
        String value() default "";
    }

    /** Vincula un parámetro de query/form al parámetro del método. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface JxParam {
        /** Nombre del parámetro HTTP. Vacío = nombre del parámetro Java. */
        String value() default "";
        boolean required() default false;
    }

    /** Deserializa el cuerpo JSON de la petición al parámetro del método. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface JxBody {}

    /** Activa la validación {@link JxValidation} sobre el parámetro anotado con {@link JxBody}. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface JxValid {}

    // ── Inyección de dependencias ────────────────────────────────────────

    /** Marca una clase como servicio singleton gestionado por {@link JxServiceRegistry}. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxService {}

    /** Marca un campo para inyección automática por {@link JxServiceRegistry}. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    public @interface JxInject {}

    // ── Manejo de excepciones ────────────────────────────────────────────

    /**
     * Marca un método como manejador de excepción dentro de un controlador.
     * El método debe recibir la excepción como primer parámetro.
     *
     * <pre>
     *   &#64;JxExceptionHandler(IllegalArgumentException.class)
     *   public ActionResult handleBadArg(IllegalArgumentException ex) {
     *       return json(Map.of("error", ex.getMessage()));
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxExceptionHandler {
        Class<? extends Throwable>[] value();
    }

    /**
     * Marca una clase como manejador global de excepciones.
     * Sus métodos {@code @JxExceptionHandler} aplican a todos los controladores.
     *
     * <pre>
     *   &#64;JxControllerAdvice
     *   public class GlobalExceptionHandler {
     *       &#64;JxExceptionHandler(JxException.class)
     *       public ActionResult handle(JxException ex) {
     *           return ActionResult.json(Map.of("error", ex.getMessage())).status(ex.getStatus());
     *       }
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxControllerAdvice {}

    // ── Estado de respuesta y cabeceras ──────────────────────────────────

    /**
     * Establece el código HTTP de respuesta para el método o controlador.
     *
     * <pre>
     *   &#64;JxResponseStatus(201)
     *   &#64;JxPostMapping("save")
     *   public ActionResult save(&#64;JxBody UserDto dto) { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface JxResponseStatus {
        int value();
        String reason() default "";
    }

    // ── Parámetros adicionales ───────────────────────────────────────────

    /** Vincula una cabecera HTTP al parámetro del método. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface JxRequestHeader {
        String value();
        boolean required()     default false;
        String defaultValue()  default "";
    }

    /** Vincula el valor de una cookie al parámetro del método. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface JxCookieValue {
        String value();
        boolean required()     default false;
        String defaultValue()  default "";
    }

    // ── Tareas programadas ───────────────────────────────────────────────

    /**
     * Programa la ejecución periódica de un método de un {@code @JxService}.
     * Usar {@code fixedDelay} o {@code fixedRate} en milisegundos.
     *
     * <pre>
     *   &#64;JxService
     *   public class CleanupService {
     *       &#64;JxScheduled(fixedDelay = 60_000, initialDelay = 5_000)
     *       public void purgeExpired() { ... }
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxScheduled {
        /** Milisegundos entre el fin de una ejecución y el inicio de la siguiente. */
        long fixedDelay()   default -1;
        /** Milisegundos entre inicios de ejecución (puede solaparse). */
        long fixedRate()    default -1;
        /** Milisegundos de espera antes de la primera ejecución. */
        long initialDelay() default 0;
        /**
         * Expresión cron de 5 campos: {@code "min hora dom mes dow"} (dow 0=domingo).
         * Ejemplos: {@code "0 3 * * *"} = cada día a las 3 AM,
         *           {@code "0 9 * * 1"} = cada lunes a las 9 AM,
         *           {@code "&#42;/15 * * * *"} = cada 15 minutos.
         */
        String cron() default "";
    }

    // ── Transacciones ────────────────────────────────────────────────────

    /**
     * Envuelve el método o todos los métodos del controlador en una transacción JDBC.
     * Todas las instancias de {@link JxDB} creadas en el mismo hilo comparten
     * la misma conexión a través de {@link JxTransaction}.
     *
     * <pre>
     *   &#64;JxTransactional
     *   &#64;JxPostMapping("transfer")
     *   public ActionResult transfer(&#64;JxBody TransferDto dto) {
     *       accountService.debit(dto.from(), dto.amount());   // mismo JxDB internamente
     *       accountService.credit(dto.to(), dto.amount());    // misma tx
     *       return json(Map.of("ok", true));
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface JxTransactional {
        boolean readOnly() default false;
    }

    // ── Caché ────────────────────────────────────────────────────────────

    /**
     * Cachea el resultado del método con TTL. Solo actúa en GET; se omite en POST/PUT/DELETE.
     * Clave = {@code key} o, si vacío, {@code método:URI[?query]}.
     *
     * <pre>
     *   &#64;JxCacheable(value = "products", ttl = 60)
     *   &#64;JxGetMapping("list")
     *   public ActionResult list() { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxCacheable {
        String value();
        String key() default "";
        long ttl() default 300;
    }

    /**
     * Invalida entradas de {@link JxCache} tras ejecutar el método.
     *
     * <pre>
     *   &#64;JxCacheEvict(value = "products")
     *   &#64;JxPostMapping("save")
     *   public ActionResult save(&#64;JxBody ProductDto dto) { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxCacheEvict {
        String value();
        String key() default "";
    }

    // ── Inyección de propiedades ─────────────────────────────────────────

    /**
     * Inyecta el valor de una propiedad de {@code application.properties} en el campo.
     * Soporta valor por defecto con la sintaxis {@code ${clave:default}}.
     *
     * <pre>
     *   &#64;JxService
     *   public class MailService {
     *       &#64;JxValue("${mail.host:localhost}")
     *       private String host;
     *
     *       &#64;JxValue("${mail.port:25}")
     *       private int port;
     *
     *       &#64;JxValue("${app.name}")
     *       private String appName;
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    public @interface JxValue {
        /** Expresión: {@code "${clave}"}, {@code "${clave:default}"} o valor literal. */
        String value();
    }

    // ── Eventos ──────────────────────────────────────────────────────────

    /**
     * Marca un método de un {@code @JxService} como listener de eventos publicados
     * con {@link JxEventBus#publish(Object)}.
     * El método debe recibir exactamente un parámetro: el tipo del evento.
     *
     * <pre>
     *   &#64;JxService
     *   public class AuditService {
     *       &#64;JxEventListener
     *       public void onUserCreated(UserCreatedEvent e) {
     *           log("New user: " + e.email());
     *       }
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxEventListener {}

    // ── Controlador REST (JSON-first) ────────────────────────────────────

    /**
     * Combina {@code @JxControllerMapping} con semántica JSON-first.
     * El {@code value()} es el prefijo de ruta igual que en {@code @JxControllerMapping}.
     * Todos los retornos de métodos se serializan a JSON automáticamente
     * (el framework ya lo hace por defecto para tipos desconocidos).
     *
     * <pre>
     *   &#64;JxRestController("api/products")
     *   public class ProductController extends JxController {
     *
     *       &#64;JxGetMapping("{id}")
     *       public Product get(&#64;JxPathVar int id) { return service.findById(id); }
     *
     *       &#64;JxPostMapping
     *       public Product create(&#64;JxBody &#64;JxValid ProductDto dto) { return service.save(dto); }
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxRestController {
        String value() default "";
    }

    // ── Mapeo de entidades (JxRepository) ───────────────────────────────

    /**
     * Marca el campo como clave primaria de la entidad.
     * Si no se usa esta anotación, se busca un campo llamado {@code id} por convención.
     *
     * <pre>
     *   &#64;JxId
     *   public long userId;
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    public @interface JxId {}

    /**
     * Mapea un campo de la entidad a un nombre de columna diferente.
     *
     * <pre>
     *   &#64;JxColumn("user_name")
     *   public String name;
     *
     *   &#64;JxColumn(value = "created_at", updatable = false)
     *   public String createdAt;
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    public @interface JxColumn {
        /** Nombre de la columna en la BD. Vacío = nombre del campo. */
        String value()       default "";
        /** Si {@code false}, el campo se omite en INSERT. */
        boolean insertable() default true;
        /** Si {@code false}, el campo se omite en UPDATE. */
        boolean updatable()  default true;
    }

    /**
     * Especifica el nombre de tabla para una entidad.
     * Si no se usa, se deriva del nombre de clase ({@code UserOrder} → {@code user_orders}).
     *
     * <pre>
     *   &#64;JxTable("tbl_users")
     *   public class User { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxTable {
        String value();
    }

    // ── Interceptores por controlador ────────────────────────────────────

    /**
     * Marca un método del controlador para ejecutarse <em>antes</em> de cada acción.
     * Si retorna {@link ActionResult} o {@link Boolean#FALSE}, la acción se cancela.
     *
     * <pre>
     *   &#64;JxBeforeAction
     *   public ActionResult requireLogin() {
     *       if (session("user") == null) return redirect("auth/login");
     *       return null;   // null = continuar
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxBeforeAction {
        /** Métodos de acción que aplican. Vacío = todos. */
        String[] only() default {};
        /** Métodos de acción excluidos. */
        String[] except() default {};
    }

    /**
     * Marca un método del controlador para ejecutarse <em>después</em> de cada acción.
     *
     * <pre>
     *   &#64;JxAfterAction
     *   public void auditRequest() {
     *       log.info("action completed");
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxAfterAction {
        /** Métodos de acción que aplican. Vacío = todos. */
        String[] only() default {};
        /** Métodos de acción excluidos. */
        String[] except() default {};
    }

    // ── Binding de configuración ─────────────────────────────────────────

    /**
     * Vincula todas las propiedades con el prefijo dado al POJO anotado.
     * Usar con {@link JxConfigBinder#bind(Class)} o como {@code @JxService}.
     *
     * <pre>
     *   &#64;JxConfigProperties("mail")
     *   &#64;JxService
     *   public class MailConfig {
     *       public String host;     // mail.host
     *       public int    port;     // mail.port
     *       public String from;     // mail.from
     *   }
     *
     *   // En un servicio:
     *   &#64;JxInject MailConfig mailConfig;
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxConfigProperties {
        /** Prefijo de propiedad, ej. {@code "mail"} → leerá {@code mail.host}, {@code mail.port}… */
        String value();
    }

    // ── Consultas nombradas en repositorios ──────────────────────────────

    /**
     * SQL personalizado para un método de {@link JxRepository}.
     * El cuerpo del método debe llamar {@code executeQuery(params...)}.
     *
     * <pre>
     *   &#64;JxQuery("SELECT * FROM users WHERE email = ? AND status = 'active'")
     *   public List&lt;User&gt; findActiveByEmail(String email) {
     *       return executeQuery(email);
     *   }
     *
     *   &#64;JxQuery("SELECT COUNT(*) AS _cnt FROM orders WHERE user_id = ?")
     *   public long countOrders(long userId) {
     *       return executeCount(userId);
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxQuery {
        /** SQL con {@code ?} como placeholder. */
        String value();
    }

    // ── Borrado lógico ───────────────────────────────────────────────────

    /**
     * Activa el borrado lógico en la entidad.
     * {@code deleteById()} hace UPDATE en lugar de DELETE.
     * Todos los SELECT del repositorio excluyen automáticamente filas marcadas.
     *
     * <pre>
     *   &#64;JxSoftDelete(column = "deleted", deletedValue = "1", activeValue = "0")
     *   public class User {
     *       &#64;JxId public long id;
     *       public String name;
     *       public int deleted;
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxSoftDelete {
        /** Nombre de la columna de borrado lógico. */
        String column()       default "deleted";
        /** Valor que indica que el registro está eliminado. */
        String deletedValue() default "1";
        /** Valor que indica que el registro está activo (para el filtro automático). */
        String activeValue()  default "0";
    }

    // ── Scope de servicios ───────────────────────────────────────────────

    /** Tipos de scope para {@link JxScope}. */
    enum ScopeType { SINGLETON, PROTOTYPE }

    /**
     * Define el scope de un {@code @JxService}.
     * {@code PROTOTYPE} crea una nueva instancia en cada inyección.
     *
     * <pre>
     *   &#64;JxService
     *   &#64;JxScope(JxMapping.ScopeType.PROTOTYPE)
     *   public class RequestContext { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxScope {
        ScopeType value() default ScopeType.SINGLETON;
    }

    // ── Multi-datasource ─────────────────────────────────────────────────

    /**
     * Indica que el servicio o repositorio usa un datasource secundario registrado en
     * {@link JxDataSourceRegistry}.
     *
     * <pre>
     *   &#64;JxService
     *   &#64;JxDS("reporting")
     *   public class ReportService {
     *       &#64;JxInject JxDB db;   // usa el datasource "reporting"
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.FIELD})
    public @interface JxDS {
        /** Nombre del datasource registrado en {@link JxDataSourceRegistry}. */
        String value();
    }

    // ── Atributos del modelo de vista ────────────────────────────────────

    /**
     * Método del controlador cuyo valor de retorno se agrega automáticamente al
     * modelo de la vista (atributo de request) antes de renderizar.
     * Se ejecuta para todas las acciones del controlador (o las indicadas en {@code only}).
     *
     * <pre>
     *   &#64;JxModelAttr("currentUser")
     *   public User loadCurrentUser() {
     *       return userService.findById((long) session("userId"));
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxModelAttr {
        /** Nombre del atributo en la request. Vacío = nombre del método. */
        String value() default "";
        /** Acciones a las que aplica. Vacío = todas. */
        String[] only() default {};
    }

    // ── Retry ────────────────────────────────────────────────────────────

    /**
     * Reintenta la ejecución del método en caso de excepción.
     *
     * <pre>
     *   &#64;JxRetry(attempts = 3, backoff = 500)
     *   &#64;JxPostMapping("sync")
     *   public ActionResult syncData() { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxRetry {
        /** Número máximo de intentos (incluye el primero). */
        int attempts()  default 3;
        /** Milisegundos de espera entre intentos. */
        long backoff()  default 500;
        /** Tipos de excepción que disparan el reintento. Vacío = todas. */
        Class<? extends Throwable>[] on() default {};
    }

    // ── Ejecución asíncrona ──────────────────────────────────────────────

    /**
     * La acción del controlador se ejecuta en un hilo separado.
     * La respuesta HTTP se devuelve inmediatamente con {@code 202 Accepted}.
     *
     * <pre>
     *   &#64;JxAsync
     *   &#64;JxPostMapping("report/generate")
     *   public ActionResult generateReport(&#64;JxBody ReportRequest req) { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface JxAsync {}

    // ── Rate limiting ────────────────────────────────────────────────────

    /**
     * Limita la tasa de peticiones por IP en el método o controlador.
     *
     * <pre>
     *   &#64;JxRateLimit(requests = 10, window = 60)   // 10 req / minuto
     *   &#64;JxPostMapping("login")
     *   public ActionResult login(&#64;JxBody LoginDto dto) { ... }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface JxRateLimit {
        /** Número máximo de peticiones permitidas en el intervalo. */
        int requests()  default 100;
        /** Ventana de tiempo en segundos. */
        long window()   default 60;
    }

    // ── Backend de caché ─────────────────────────────────────────────────

    /**
     * Marca un {@code @JxService} que implementa {@link JxCacheBackend} como
     * el proveedor global de caché. Reemplaza la implementación en memoria predeterminada.
     *
     * <pre>
     *   &#64;JxService
     *   &#64;JxCacheManager
     *   public class RedisCacheBackend implements JxCacheBackend {
     *       // implementar put, fetch, evict, clear, has
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxCacheManager {}

    // ── WebSocket ───────────────────────────────────────────────

    /**
     * Declara un endpoint WebSocket. La clase debe extender {@link JxWebSocket}.
     * El path soporta variables: {@code /ws/chat/{sala}}.
     *
     * <p>Ejemplo:
     * <pre>
     *   &#64;JxWsEndpoint("/ws/chat/{sala}")
     *   public class ChatEndpoint extends JxWebSocket {
     *
     *       &#64;Override
     *       protected void onOpen(Session session, String... pathVars) {
     *           joinRoom(pathVars[0], session);
     *           broadcast(pathVars[0], session.getId() + " conectado");
     *       }
     *
     *       &#64;Override
     *       protected void onMessage(Session session, String message) {
     *           String sala = (String) session.getUserProperties().get("sala");
     *           broadcast(sala, session.getId() + ": " + message);
     *       }
     *   }
     * </pre>
     */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface JxWsEndpoint {
        /** Path del endpoint, ej. {@code "/ws/notifications"} o {@code "/ws/room/{id}"}. */
        String value();
        /** Subprotocolos WebSocket soportados. */
        String[] subprotocols() default {};
    }
}
