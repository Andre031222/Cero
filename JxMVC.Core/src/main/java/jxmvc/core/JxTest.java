/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  improved : R. Andre Vilca Solorzano

package jxmvc.core;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * Utilidades de testing para controladores y servicios — sin Tomcat, sin MockMvc.
 * Permite probar acciones del controlador directamente invocando el pipeline de Lux.
 *
 * <pre>
 *   // Test de un endpoint REST
 *   JxTest.Response resp = JxTest.invoke(UserController.class)
 *       .method("GET")
 *       .path("/api/users/1")
 *       .pathVar("id", "1")
 *       .run("findById");
 *
 *   assert resp.status() == 200;
 *   assert resp.bodyContains("\"name\"");
 *
 *   // Test con body JSON
 *   JxTest.Response resp = JxTest.invoke(UserController.class)
 *       .method("POST")
 *       .body("{\"name\":\"Ana\",\"email\":\"ana@test.com\"}")
 *       .run("save");
 *
 *   assert resp.status() == 201;
 *
 *   // Test de servicio directo
 *   JxTest.inject(userService);  // inyecta dependencias en el servicio
 *   List&lt;User&gt; users = userService.findAll();
 *   assert users != null;
 * </pre>
 */
public final class JxTest {

    private JxTest() {}

    /**
     * Inicia un builder para invocar una acción del controlador dado.
     */
    public static Builder invoke(Class<? extends JxController> controllerClass) {
        return new Builder(controllerClass);
    }

    /**
     * Inyecta las dependencias en el objeto (servicio, controlador, etc.)
     * usando {@link JxServiceRegistry}, igual que hace el framework en producción.
     */
    public static void inject(Object target) {
        JxServiceRegistry.inject(target);
    }

    /**
     * Registra un mock de servicio para que sea inyectado en lugar del real.
     *
     * <pre>
     *   UserRepository mockRepo = new UserRepositoryMock();
     *   JxTest.mockService(UserRepository.class, mockRepo);
     * </pre>
     */
    public static void mockService(Class<?> type, Object mock) {
        JxServiceRegistry.register(type, mock);
    }

    // ── Builder ───────────────────────────────────────────────────────────

    public static final class Builder {

        private final Class<? extends JxController> controllerClass;
        private String              httpMethod = "GET";
        private String              body       = "";
        private Map<String, String> pathVars   = new LinkedHashMap<>();
        private Map<String, String> params     = new LinkedHashMap<>();
        private Map<String, String> headers    = new LinkedHashMap<>();
        private String              path       = "/";

        private Builder(Class<? extends JxController> cls) {
            this.controllerClass = cls;
        }

        public Builder method(String method)         { this.httpMethod = method; return this; }
        public Builder path(String path)             { this.path = path; return this; }
        public Builder body(String json)             { this.body = json; return this; }
        public Builder pathVar(String k, String v)   { pathVars.put(k, v); return this; }
        public Builder param(String k, String v)     { params.put(k, v); return this; }
        public Builder header(String k, String v)    { headers.put(k, v); return this; }

        /**
         * Ejecuta la acción con el nombre dado y retorna la respuesta simulada.
         *
         * @param actionName nombre del método en el controlador
         */
        public Response run(String actionName) {
            return run(actionName, new Object[0]);
        }

        /**
         * Ejecuta la acción pasando argumentos directos (sin binding de HTTP).
         */
        public Response run(String actionName, Object... directArgs) {
            try {
                // Instanciar controlador
                JxController ctrl = controllerClass.getDeclaredConstructor().newInstance();
                JxServiceRegistry.inject(ctrl);

                // Mock request/response
                MockRequest  mockReq  = new MockRequest(httpMethod, path, body, params, headers);
                MockResponse mockResp = new MockResponse();
                JxRequest    jxReq    = mockReq.toJxRequest(mockResp);
                JxResponse   jxResp   = new JxResponse(null, null, "");

                // No podemos bindContext con mocks sin HttpServletRequest real
                // Usar reflexión para settear los campos internos directamente
                try {
                    java.lang.reflect.Field reqField = JxController.class.getDeclaredField("model");
                    reqField.setAccessible(true);
                    reqField.set(ctrl, jxReq);
                } catch (Exception ignored) {}

                // Encontrar el método
                Method method = findMethod(controllerClass, actionName);
                if (method == null) {
                    return new Response(404, "Acción no encontrada: " + actionName, Map.of());
                }
                method.setAccessible(true);

                // Construir plan mínimo para el path vars
                BaseDispatchPlan plan = new BaseDispatchPlan(
                        actionName, actionName, new String[0],
                        pathVars, controllerClass, method);

                // Invocar
                Object result;
                if (directArgs.length > 0) {
                    result = method.invoke(ctrl, directArgs);
                } else if (method.getParameterCount() == 0) {
                    result = method.invoke(ctrl);
                } else {
                    // Binding básico de parámetros (path vars, query params, body)
                    Object[] args = buildTestArgs(plan, method, jxReq);
                    result = method.invoke(ctrl, args);
                }

                // Determinar status y body
                if (result instanceof ActionResult ar) {
                    return new Response(ar.hasCustomStatus() ? ar.httpStatus() : 200,
                            ar.payload(), Map.of());
                }
                if (result instanceof String s) {
                    return new Response(200, s, Map.of());
                }
                if (result == null) {
                    return new Response(204, "", Map.of());
                }
                return new Response(200, JxJson.toJson(result), Map.of());

            } catch (java.lang.reflect.InvocationTargetException ex) {
                Throwable cause = ex.getTargetException() != null ? ex.getTargetException() : ex;
                if (cause instanceof JxException jx)
                    return new Response(jx.getStatus(), jx.getMessage(), Map.of());
                return new Response(500, cause.getMessage(), Map.of());
            } catch (Exception ex) {
                return new Response(500, ex.getMessage(), Map.of());
            }
        }

        private static Method findMethod(Class<?> cls, String name) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            return null;
        }

        private Object[] buildTestArgs(BaseDispatchPlan plan, Method method, JxRequest jxReq) {
            Parameter[] params = method.getParameters();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Parameter p = params[i];
                JxMapping.JxPathVar pv = p.getAnnotation(JxMapping.JxPathVar.class);
                JxMapping.JxParam   qp = p.getAnnotation(JxMapping.JxParam.class);
                JxMapping.JxBody    b  = p.getAnnotation(JxMapping.JxBody.class);

                if (pv != null) {
                    String name = pv.value().isBlank() ? p.getName() : pv.value();
                    String val  = pathVars.get(name);
                    args[i] = convertSimple(val, p.getType());
                } else if (qp != null) {
                    String name = qp.value().isBlank() ? p.getName() : qp.value();
                    args[i] = convertSimple(this.params.get(name), p.getType());
                } else if (b != null) {
                    args[i] = JxJson.fromJson(this.body, p.getType());
                    if (p.getAnnotation(JxMapping.JxValid.class) != null && args[i] != null) {
                        JxValidation.validate(args[i]);
                    }
                } else {
                    args[i] = defaultVal(p.getType());
                }
            }
            return args;
        }

        private Object convertSimple(String val, Class<?> type) {
            if (val == null) return defaultVal(type);
            if (type == String.class) return val;
            try {
                if (type == int.class     || type == Integer.class) return Integer.parseInt(val);
                if (type == long.class    || type == Long.class)    return Long.parseLong(val);
                if (type == double.class  || type == Double.class)  return Double.parseDouble(val);
                if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(val);
            } catch (NumberFormatException ignored) {}
            return defaultVal(type);
        }

        private Object defaultVal(Class<?> type) {
            if (type == int.class || type == long.class) return 0;
            if (type == boolean.class) return false;
            if (type == double.class || type == float.class) return 0.0;
            return null;
        }
    }

    // ── Response ──────────────────────────────────────────────────────────

    /**
     * Respuesta de una invocación de prueba.
     */
    public record Response(int status, String body, Map<String, String> headers) {

        /** {@code true} si el status es 2xx. */
        public boolean isOk() { return status >= 200 && status < 300; }

        /** {@code true} si el body contiene el fragmento dado. */
        public boolean bodyContains(String fragment) {
            return body != null && body.contains(fragment);
        }

        /** Deserializa el body JSON al tipo dado usando {@link JxJson}. */
        public <T> T as(Class<T> type) {
            return JxJson.fromJson(body, type);
        }

        @Override
        public String toString() {
            return "JxTest.Response{status=" + status + ", body=" + body + "}";
        }
    }

    // ── Mock interno ──────────────────────────────────────────────────────

    /** Mock mínimo de request para los tests. */
    private record MockRequest(
            String method, String path, String body,
            Map<String, String> params, Map<String, String> headers) {

        JxRequest toJxRequest(MockResponse resp) {
            // JxRequest necesita HttpServletRequest real — usamos una instancia vacía
            // con los datos inyectados vía el body almacenado
            return new JxTestRequest(body, params);
        }
    }

    private static final class MockResponse {}

    /** Extensión mínima de JxRequest para pruebas. */
    static final class JxTestRequest extends JxRequest {
        private final String       bodyStr;
        private final Map<String, String> qParams;

        JxTestRequest(String body, Map<String, String> params) {
            super(null, null, new String[0]);
            this.bodyStr = body != null ? body : "";
            this.qParams = params != null ? params : Map.of();
        }

        @Override public String body()           { return bodyStr; }
        @Override public String param(String k)  { return qParams.get(k); }
    }
}
