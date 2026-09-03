package cero.adapter.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dobles de HttpServletRequest, HttpServletResponse y HttpSession construidos con proxies.
 * Solo implementan lo que el adaptador usa, que es exactamente lo que hay que probar.
 */
final class Doble {

    private Doble() {
    }

    static final class Peticion {
        String metodo = "GET";
        String uri = "/";
        String contextPath = "";
        String consulta;
        String protocolo = "HTTP/1.1";
        String remoto = "10.0.0.7";
        boolean segura;
        byte[] cuerpo = new byte[0];
        String juego;
        final Map<String, List<String>> cabeceras = new LinkedHashMap<>();
        final Map<String, String> galletas = new LinkedHashMap<>();
        Sesion sesion;
        boolean crearSesionDevuelveNull;

        Peticion cabecera(String nombre, String valor) {
            cabeceras.computeIfAbsent(nombre, ignorado -> new ArrayList<>()).add(valor);
            return this;
        }

        Peticion galleta(String nombre, String valor) {
            galletas.put(nombre, valor);
            return this;
        }

        Peticion cuerpo(String texto) {
            cuerpo = texto.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        HttpServletRequest construir() {
            InvocationHandler manejador = (proxy, metodo, args) -> switch (metodo.getName()) {
                case "getMethod" -> this.metodo;
                case "getRequestURI" -> uri;
                case "getContextPath" -> contextPath;
                case "getQueryString" -> consulta;
                case "getProtocol" -> protocolo;
                case "getRemoteAddr" -> remoto;
                case "isSecure" -> segura;
                case "getCharacterEncoding" -> juego;
                case "getHeader" -> {
                    List<String> valores = buscar((String) args[0]);
                    yield valores.isEmpty() ? null : valores.get(0);
                }
                case "getHeaders" -> Collections.enumeration(buscar((String) args[0]));
                case "getHeaderNames" -> Collections.enumeration(cabeceras.keySet());
                case "getCookies" -> galletas.isEmpty() ? null : galletas.entrySet().stream()
                        .map(e -> new jakarta.servlet.http.Cookie(e.getKey(), e.getValue()))
                        .toArray(jakarta.servlet.http.Cookie[]::new);
                case "getInputStream" -> flujoEntrada(cuerpo);
                case "getSession" -> {
                    boolean crear = args == null || args.length == 0 || (boolean) args[0];
                    if (!crear && sesion == null) {
                        yield null;
                    }
                    if (crearSesionDevuelveNull) {
                        yield null;
                    }
                    if (sesion == null) {
                        sesion = new Sesion();
                    }
                    yield sesion.construir();
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "PeticionDoble";
                default -> valorPorDefecto(metodo.getReturnType());
            };
            return (HttpServletRequest) Proxy.newProxyInstance(Doble.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, manejador);
        }

        private List<String> buscar(String nombre) {
            for (Map.Entry<String, List<String>> entrada : cabeceras.entrySet()) {
                if (entrada.getKey().equalsIgnoreCase(nombre)) {
                    return entrada.getValue();
                }
            }
            return List.of();
        }
    }

    static final class Respuesta {
        int estado = 200;
        String contentType;
        Integer contentLength;
        final Map<String, String> cabeceras = new LinkedHashMap<>();
        final List<String> anadidas = new ArrayList<>();
        final ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        boolean confirmada;

        String texto() {
            return cuerpo.toString(StandardCharsets.UTF_8);
        }

        HttpServletResponse construir() {
            InvocationHandler manejador = (proxy, metodo, args) -> switch (metodo.getName()) {
                case "setStatus" -> { estado = (int) args[0]; yield null; }
                case "getStatus" -> estado;
                case "setContentType" -> { contentType = (String) args[0]; yield null; }
                case "getContentType" -> contentType;
                case "setContentLength" -> { contentLength = (int) args[0]; yield null; }
                case "setHeader" -> { cabeceras.put((String) args[0], (String) args[1]); yield null; }
                case "addHeader" -> { anadidas.add(args[0] + ": " + args[1]); yield null; }
                case "getHeader" -> cabeceras.get((String) args[0]);
                case "isCommitted" -> confirmada;
                case "getOutputStream" -> flujoSalida(cuerpo);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "RespuestaDoble";
                default -> valorPorDefecto(metodo.getReturnType());
            };
            return (HttpServletResponse) Proxy.newProxyInstance(Doble.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, manejador);
        }
    }

    static final class Sesion {
        private static final AtomicLong SECUENCIA = new AtomicLong(1);

        final String id = "SESION-" + SECUENCIA.getAndIncrement();
        final Map<String, Object> atributos = new LinkedHashMap<>();
        final long creada = System.currentTimeMillis();
        boolean nueva = true;
        boolean anulada;

        HttpSession construir() {
            InvocationHandler manejador = (proxy, metodo, args) -> switch (metodo.getName()) {
                case "getId" -> id;
                case "isNew" -> nueva;
                case "getAttribute" -> atributos.get((String) args[0]);
                case "setAttribute" -> { atributos.put((String) args[0], args[1]); yield null; }
                case "removeAttribute" -> { atributos.remove((String) args[0]); yield null; }
                case "getAttributeNames" -> Collections.enumeration(atributos.keySet());
                case "invalidate" -> { anulada = true; atributos.clear(); yield null; }
                case "getCreationTime" -> creada;
                case "getLastAccessedTime" -> creada;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "SesionDoble";
                default -> valorPorDefecto(metodo.getReturnType());
            };
            return (HttpSession) Proxy.newProxyInstance(Doble.class.getClassLoader(),
                    new Class<?>[]{HttpSession.class}, manejador);
        }
    }

    private static jakarta.servlet.ServletInputStream flujoEntrada(byte[] datos) {
        ByteArrayInputStream origen = new ByteArrayInputStream(datos);
        return new jakarta.servlet.ServletInputStream() {
            @Override public int read() { return origen.read(); }
            @Override public int read(byte[] destino, int desde, int cuantos) {
                return origen.read(destino, desde, cuantos);
            }
            @Override public boolean isFinished() { return origen.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener oyente) { }
        };
    }

    private static jakarta.servlet.ServletOutputStream flujoSalida(ByteArrayOutputStream destino) {
        return new jakarta.servlet.ServletOutputStream() {
            @Override public void write(int b) { destino.write(b); }
            @Override public void write(byte[] datos, int desde, int cuantos) {
                destino.write(datos, desde, cuantos);
            }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener oyente) { }
        };
    }

    private static Object valorPorDefecto(Class<?> tipo) {
        if (!tipo.isPrimitive()) {
            return null;
        }
        if (tipo == boolean.class) {
            return false;
        }
        if (tipo == void.class) {
            return null;
        }
        if (tipo == long.class) {
            return 0L;
        }
        if (tipo == double.class) {
            return 0d;
        }
        return 0;
    }
}
