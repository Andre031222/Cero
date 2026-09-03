package cero.core;

import cero.http.HttpMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera la especificación OpenAPI 3.0 de la aplicación a partir de sus rutas.
 *
 * <pre>
 *   Cero.app()
 *      .controllers(ArticuloController.class)
 *      .routes(r -&gt; r.get("/openapi.json", OpenApi.describing(router).title("Catálogo").endpoint()))
 *      .start();
 * </pre>
 *
 * <p>Se construye desde el {@link Router}, no escaneando el disco, así que describe exactamente
 * lo que la aplicación sirve — incluidas las rutas declaradas con lambda.
 */
public final class OpenApi {

    private final Router router;

    private String title = "API";
    private String version = "1.0.0";
    private String description = "";
    private String server = "";

    private OpenApi(Router router) {
        this.router = router;
    }

    public static OpenApi describing(Router router) {
        if (router == null) {
            throw new IllegalArgumentException("hace falta un router que describir");
        }
        return new OpenApi(router);
    }

    public OpenApi title(String valor) {
        title = valor;
        return this;
    }

    public OpenApi version(String valor) {
        version = valor;
        return this;
    }

    public OpenApi description(String valor) {
        description = valor;
        return this;
    }

    public OpenApi server(String valor) {
        server = valor;
        return this;
    }

    public Endpoint endpoint() {
        return context -> Result.raw(json());
    }

    public String json() {
        Map<String, Object> raiz = new LinkedHashMap<>();
        raiz.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("version", version);
        if (!description.isEmpty()) {
            info.put("description", description);
        }
        raiz.put("info", info);

        if (!server.isEmpty()) {
            raiz.put("servers", List.of(Map.of("url", server)));
        }

        Map<String, Object> esquemas = new LinkedHashMap<>();
        raiz.put("paths", caminos(esquemas));
        if (!esquemas.isEmpty()) {
            raiz.put("components", Map.of("schemas", esquemas));
        }
        return Json.write(raiz);
    }

    private Map<String, Object> caminos(Map<String, Object> esquemas) {
        Map<String, Object> caminos = new LinkedHashMap<>();
        for (RouteEntry ruta : router.routes()) {
            if (ruta.verb() == HttpMethod.HEAD) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> operaciones = (Map<String, Object>) caminos
                    .computeIfAbsent(ruta.pattern().raw(), clave -> new LinkedHashMap<String, Object>());
            operaciones.put(ruta.verb().name().toLowerCase(), operacion(ruta, esquemas));
        }
        return caminos;
    }

    private Map<String, Object> operacion(RouteEntry ruta, Map<String, Object> esquemas) {
        Map<String, Object> operacion = new LinkedHashMap<>();

        if (ruta.isLambda()) {
            operacion.put("summary", ruta.pattern().raw());
            operacion.put("operationId", identificador(ruta));
            operacion.put("parameters", parametrosDeRuta(ruta, List.of()));
            operacion.put("responses", respuestas(null, esquemas));
            return operacion;
        }

        Method accion = ruta.action();
        operacion.put("summary", accion.getName());
        operacion.put("operationId", identificador(ruta));
        operacion.put("tags", List.of(etiqueta(ruta.controller())));

        List<Map<String, Object>> parametros = parametrosDeRuta(ruta, List.of(accion.getParameters()));
        if (!parametros.isEmpty()) {
            operacion.put("parameters", parametros);
        }

        Map<String, Object> cuerpo = cuerpoDePeticion(accion, esquemas);
        if (cuerpo != null) {
            operacion.put("requestBody", cuerpo);
        }
        if (ruta.requiresAuth()) {
            operacion.put("security", List.of(Map.of("bearerAuth", List.of())));
        }
        operacion.put("responses", respuestas(accion.getReturnType(), esquemas));
        return operacion;
    }

    private List<Map<String, Object>> parametrosDeRuta(RouteEntry ruta, List<Parameter> parametros) {
        List<Map<String, Object>> salida = new ArrayList<>();
        List<String> yaPuestos = new ArrayList<>();

        for (Parameter parametro : parametros) {
            Path path = parametro.getAnnotation(Path.class);
            if (path != null) {
                salida.add(parametro("path", path.value(), true, parametro.getType()));
                yaPuestos.add(path.value());
                continue;
            }
            Query query = parametro.getAnnotation(Query.class);
            if (query != null) {
                salida.add(parametro("query", query.value(), false, parametro.getType()));
                continue;
            }
            Header header = parametro.getAnnotation(Header.class);
            if (header != null) {
                salida.add(parametro("header", header.value(), false, parametro.getType()));
            }
        }

        // Las del patrón sin @Path también son parte del contrato.
        for (String variable : variablesDe(ruta.pattern().raw())) {
            if (!yaPuestos.contains(variable)) {
                salida.add(parametro("path", variable, true, String.class));
            }
        }
        return salida;
    }

    private static List<String> variablesDe(String patron) {
        List<String> nombres = new ArrayList<>();
        int desde = 0;
        while (true) {
            int abre = patron.indexOf('{', desde);
            if (abre < 0) {
                return nombres;
            }
            int cierra = patron.indexOf('}', abre);
            if (cierra < 0) {
                return nombres;
            }
            nombres.add(patron.substring(abre + 1, cierra));
            desde = cierra + 1;
        }
    }

    private static Map<String, Object> parametro(String donde, String nombre, boolean obligatorio, Class<?> tipo) {
        Map<String, Object> parametro = new LinkedHashMap<>();
        parametro.put("name", nombre);
        parametro.put("in", donde);
        parametro.put("required", obligatorio);
        parametro.put("schema", tipoSimple(tipo));
        return parametro;
    }

    private Map<String, Object> cuerpoDePeticion(Method accion, Map<String, Object> esquemas) {
        for (Parameter parametro : accion.getParameters()) {
            if (!parametro.isAnnotationPresent(Body.class)) {
                continue;
            }
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("required", true);
            cuerpo.put("content", Map.of("application/json",
                    Map.of("schema", esquema(parametro.getType(), esquemas))));
            return cuerpo;
        }
        return null;
    }

    private Map<String, Object> respuestas(Class<?> devuelto, Map<String, Object> esquemas) {
        Map<String, Object> respuestas = new LinkedHashMap<>();
        if (devuelto == null || devuelto == void.class || devuelto == Void.class) {
            respuestas.put("204", Map.of("description", "sin contenido"));
            return respuestas;
        }
        respuestas.put("200", Map.of(
                "description", "correcto",
                "content", Map.of("application/json", Map.of("schema", esquema(devuelto, esquemas)))));
        return respuestas;
    }

    /** Registra los records en {@code components/schemas} y devuelve una referencia. */
    private Map<String, Object> esquema(Class<?> tipo, Map<String, Object> esquemas) {
        if (tipo.isRecord()) {
            String nombre = tipo.getSimpleName();
            if (!esquemas.containsKey(nombre)) {
                // Hueco reservado antes de recorrer: un record que se contenga no entra en bucle.
                esquemas.put(nombre, Map.of());
                Map<String, Object> propiedades = new LinkedHashMap<>();
                for (RecordComponent componente : tipo.getRecordComponents()) {
                    propiedades.put(componente.getName(), esquema(componente.getType(), esquemas));
                }
                esquemas.put(nombre, Map.of("type", "object", "properties", propiedades));
            }
            return Map.of("$ref", "#/components/schemas/" + nombre);
        }
        if (Iterable.class.isAssignableFrom(tipo) || tipo.isArray()) {
            Class<?> elemento = tipo.isArray() ? tipo.getComponentType() : Object.class;
            return Map.of("type", "array", "items",
                    elemento == Object.class ? Map.of() : esquema(elemento, esquemas));
        }
        return tipoSimple(tipo);
    }

    private static Map<String, Object> tipoSimple(Class<?> tipo) {
        if (tipo == boolean.class || tipo == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (tipo == int.class || tipo == Integer.class || tipo == long.class || tipo == Long.class
                || tipo == short.class || tipo == Short.class) {
            return Map.of("type", "integer");
        }
        if (tipo == double.class || tipo == Double.class || tipo == float.class || tipo == Float.class) {
            return Map.of("type", "number");
        }
        if (Map.class.isAssignableFrom(tipo)) {
            return Map.of("type", "object");
        }
        if (Iterable.class.isAssignableFrom(tipo) || tipo.isArray()) {
            return Map.of("type", "array", "items", Map.of());
        }
        return Map.of("type", "string");
    }

    private static String identificador(RouteEntry ruta) {
        if (ruta.isLambda()) {
            return ruta.verb().name().toLowerCase()
                    + ruta.pattern().raw().replace('/', '_').replace("{", "").replace("}", "");
        }
        return ruta.controller().getSimpleName() + "_" + ruta.action().getName();
    }

    private static String etiqueta(Class<?> controlador) {
        String nombre = controlador.getSimpleName();
        return nombre.endsWith("Controller")
                ? nombre.substring(0, nombre.length() - "Controller".length())
                : nombre;
    }
}
