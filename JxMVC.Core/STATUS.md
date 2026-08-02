# JxMVC.Core — v3.4.0

Framework MVC ligero para Jakarta EE 10 / Tomcat 10.1+.
Cero dependencias externas en runtime (solo el JDBC driver de la BD en uso).

---

## Clases del framework

```
jxmvc.core/
|
|-- Nucleo HTTP
|   |-- MainLxServlet        Servlet principal. Pipeline de 15 etapas.
|   |-- BaseDispatcher        Router: anotaciones, plantillas {var}, convencion /ctrl/action/args
|   |-- BaseDispatchPlan      Plan de despacho inmutable (record)
|   |-- BaseCorsResolver      Politica CORS declarativa por anotacion
|   |-- BaseSanitizer         Limpieza XSS automatica en params y args
|   |-- BaseDbResolver        Lectura de application.properties
|   |-- JxController          Clase base de todos los controladores
|   |-- JxRequest             Contexto de peticion: params, args, body, upload, sesion
|   |-- JxResponse            Contexto de respuesta: text, html, json, redirect, status
|   |-- ActionResult          Resultado tipado de una accion (VIEW | TEXT | JSON | REDIRECT)
|   |-- JxHttp                Utilidades HTTP
|   `-- JxException           Excepcion HTTP tipada con codigo de estado
|
|-- Anotaciones
|   `-- JxMapping             Todas las anotaciones del framework:
|                               routing, auth, CORS, DI, transacciones, cache,
|                               async, retry, rate limit, scheduling, eventos,
|                               validacion, soft delete, config, interceptores
|
|-- Persistencia
|   |-- JxDB                  Acceso JDBC (PostgreSQL / MySQL / SQL Server)
|   |-- JxRepository<T,ID>   CRUD generico con soft delete, paginacion y @JxQuery
|   |-- JxTransaction         Transacciones JDBC via ThreadLocal
|   |-- JxPool                Pool de conexiones JDBC global
|   |-- JxDataSourceRegistry  Registro de multiples datasources nombrados
|   |-- DBRow                 Fila de BD (LinkedHashMap ordenado)
|   |-- DBRowSet              Conjunto de filas
|   `-- JxPageResult          Resultado paginado: data, total, total_pages
|
|-- Inyeccion de dependencias
|   |-- JxServiceRegistry     DI: singleton y prototype, @JxInject, deteccion de dependencias circulares
|   |-- JxConfigBinder        Binding de application.properties a POJOs
|   `-- JxEnvironment         Acceso tipado a propiedades y variables de entorno
|
|-- Seguridad
|   |-- JxSecurity            Registro global del proveedor de autenticacion
|   `-- JxAuthProvider        Interfaz enchufable de autenticacion y roles
|
|-- Resiliencia
|   |-- JxRateLimiter         Rate limiting por IP con ventana de tiempo
|   |-- JxCache               Cache en memoria con TTL y backend intercambiable
|   `-- JxCacheBackend        Interfaz para backend de cache externo (Redis, etc.)
|
|-- Observabilidad
|   |-- JxMetrics             Metricas por ruta: contador de peticiones y latencia
|   |-- JxOpenApi             Generacion de spec OpenAPI desde anotaciones
|   |-- JxLogger              Logger interno sin dependencias externas
|   `-- JxProfile             Perfiles de ejecucion (dev, prod, test)
|
|-- Asincronia y scheduling
|   |-- JxScheduler           Tareas periodicas (@JxScheduled)
|   `-- JxEventBus            Bus de eventos tipado (@JxEventListener)
|
|-- Filtros e interceptores
|   |-- JxFilters             Pipeline de filtros before/after globales
|   |-- JxFilterContext       Contexto de filtro (model, view, controller, action)
|   |-- JxAdviceRegistry      Manejador global de excepciones (@JxControllerAdvice)
|   `-- JxValidation          Validacion de POJOs (@JxValid + @JxBody)
|
|-- Serializacion
|   `-- JxJson                Serializer y parser JSON. Sin dependencias. Soporta:
|                               null, String, Number, Boolean, List, Map,
|                               DBRow, DBRowSet, POJO (via reflexion)
|
`-- Tags JSP
    |-- JxTagFor              <jx:for> iteracion en vistas JSP
    `-- JxTagIf               <jx:if> condicional en vistas JSP
```

---

## Endpoints internos

| Ruta | Descripcion |
|---|---|
| `/jx/health` | Estado del pool, scheduler y executor async |
| `/jx/info` | Version, perfil activo, JVM, servidor |
| `/jx/metrics` | Peticiones por ruta con latencia |
| `/jx/openapi` | Spec OpenAPI generada desde anotaciones |

---

## Configuracion (application.properties)

```properties
jxmvc.controllers.package = jxmvc.controllers
jxmvc.db.url              = jdbc:postgresql://host:5432/db
jxmvc.db.user             = usuario
jxmvc.db.pass             = contrasena
jxmvc.async.threads       = 8        # tamano del pool async (default 8)
jxmvc.profile             = dev      # dev | prod | test
```

---

## Ejemplos rapidos

### Controlador REST minimo

```java
@JxRestController("api/productos")
public class ProductoController extends JxController {

    @JxInject ProductoRepository repo;

    @JxGetMapping("{id}")
    public ActionResult get(@JxPathVar long id) {
        return json(repo.findById(id));
    }

    @JxPostMapping
    @JxResponseStatus(201)
    public ActionResult crear(@JxBody @JxValid ProductoDto dto) {
        return json(repo.save(dto.toEntity()));
    }

    @JxDeleteMapping("{id}")
    @JxAuth(roles = {"ADMIN"})
    public ActionResult eliminar(@JxPathVar long id) {
        repo.deleteById(id);
        return ok();
    }
}
```

### Repositorio con soft delete

```java
@JxSoftDelete(column = "eliminado", deletedValue = "1", activeValue = "0")
public class Producto {
    @JxId public long id;
    public String nombre;
    public double precio;
    public int eliminado = 0;
}

@JxService
public class ProductoRepository extends JxRepository<Producto, Long> {
    public ProductoRepository() { super("productos", Producto.class); }

    @JxQuery("SELECT * FROM productos WHERE precio < ? AND eliminado = 0")
    public List<Producto> baratos(double limite) {
        return executeQuery(limite);
    }
}
```

### Autenticacion enchufable

```java
// En un ServletContextListener:
JxSecurity.setProvider((request, roles) -> {
    Object user = request.getSession(false) != null
            ? request.getSession().getAttribute("user") : null;
    if (user == null) return false;
    if (roles.length == 0) return true;
    String userRole = ((Usuario) user).getRol();
    for (String r : roles) { if (r.equals(userRole)) return true; }
    return false;
});

// En el controlador:
@JxAuth(roles = {"ADMIN"})
@JxGetMapping("admin/panel")
public ActionResult panel() { ... }
```

### Transaccion declarativa

```java
@JxTransactional
@JxPostMapping("transferir")
public ActionResult transferir(@JxBody TransferenciaDto dto) {
    cuentaService.debitar(dto.origen(), dto.monto());
    cuentaService.acreditar(dto.destino(), dto.monto());
    return ok();
}
```

### Rate limiting y async

```java
@JxRateLimit(requests = 5, window = 60)
@JxPostMapping("login")
public ActionResult login(@JxBody LoginDto dto) { ... }

@JxAsync
@JxPostMapping("reporte/generar")
public ActionResult generarReporte(@JxBody ReporteRequest req) {
    // Se ejecuta en background. El cliente recibe 202 de inmediato.
    reporteService.generar(req);
    return ok();
}
```
