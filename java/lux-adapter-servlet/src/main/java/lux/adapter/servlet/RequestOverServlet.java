package lux.adapter.servlet;

import jakarta.servlet.http.HttpServletRequest;
import lux.http.Headers;
import lux.http.HttpException;
import lux.http.HttpMethod;
import lux.http.Part;
import lux.http.Request;
import lux.http.Session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RequestOverServlet implements Request {

    private final HttpServletRequest crudo;

    private Headers cabeceras;
    private Map<String, String> galletas;
    private List<Part> partes;
    private byte[] cuerpo;
    private SessionOverServlet sesion;

    RequestOverServlet(HttpServletRequest crudo) {
        this.crudo = crudo;
    }

    @Override
    public HttpMethod method() {
        try {
            return HttpMethod.valueOf(crudo.getMethod());
        } catch (IllegalArgumentException desconocido) {
            throw new HttpException(501, "método no soportado: " + crudo.getMethod());
        }
    }

    @Override
    public String path() {
        String completo = crudo.getRequestURI();
        String contexto = crudo.getContextPath();
        String relativo = contexto == null || contexto.isEmpty() || !completo.startsWith(contexto)
                ? completo
                : completo.substring(contexto.length());
        return relativo.isEmpty() ? "/" : relativo;
    }

    @Override
    public String rawQuery() {
        return crudo.getQueryString();
    }

    @Override
    public String query(String nombre) {
        List<String> valores = queryAll(nombre);
        return valores.isEmpty() ? null : valores.get(0);
    }

    @Override
    public List<String> queryAll(String nombre) {
        return lux.http.Url.parseQuery(rawQuery()).getOrDefault(nombre, List.of());
    }

    @Override
    public String form(String nombre) {
        List<String> valores = formAll(nombre);
        return valores.isEmpty() ? null : valores.get(0);
    }

    @Override
    public List<String> formAll(String nombre) {
        return forms().getOrDefault(nombre, List.of());
    }

    @Override
    public Map<String, List<String>> forms() {
        String tipo = header("Content-Type");
        if (tipo == null || !tipo.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            return Map.of();
        }
        return lux.http.Url.parseQuery(bodyText());
    }

    @Override
    public Headers headers() {
        if (cabeceras == null) {
            cabeceras = new Headers();
            Enumeration<String> nombres = crudo.getHeaderNames();
            while (nombres != null && nombres.hasMoreElements()) {
                String nombre = nombres.nextElement();
                Enumeration<String> valores = crudo.getHeaders(nombre);
                while (valores.hasMoreElements()) {
                    cabeceras.add(nombre, valores.nextElement());
                }
            }
        }
        return cabeceras;
    }

    @Override
    public String header(String nombre) {
        return crudo.getHeader(nombre);
    }

    @Override
    public String protocol() {
        return crudo.getProtocol();
    }

    @Override
    public String remoteAddress() {
        String remoto = crudo.getRemoteAddr();
        return remoto == null ? "" : remoto;
    }

    @Override
    public boolean secure() {
        return crudo.isSecure();
    }

    @Override
    public String cookie(String nombre) {
        return cookies().get(nombre);
    }

    @Override
    public Map<String, String> cookies() {
        if (galletas == null) {
            galletas = new LinkedHashMap<>();
            jakarta.servlet.http.Cookie[] recibidas = crudo.getCookies();
            if (recibidas != null) {
                for (jakarta.servlet.http.Cookie galleta : recibidas) {
                    galletas.putIfAbsent(galleta.getName(), galleta.getValue());
                }
            }
        }
        return galletas;
    }

    @Override
    public Session session() {
        return session(true);
    }

    @Override
    public Session session(boolean crear) {
        if (sesion != null && sesion.valid()) {
            return sesion;
        }
        var nativa = crudo.getSession(crear);
        if (nativa == null) {
            return null;
        }
        sesion = new SessionOverServlet(nativa);
        return sesion;
    }

    @Override
    public List<Part> parts() {
        if (partes == null) {
            String tipo = header("Content-Type");
            if (tipo == null || !tipo.toLowerCase().startsWith("multipart/form-data")) {
                throw new HttpException(415, "la petición no es multipart/form-data");
            }
            partes = new ArrayList<>();
            try {
                for (jakarta.servlet.http.Part nativa : crudo.getParts()) {
                    partes.add(new Part(nativa.getName(), nativa.getSubmittedFileName(),
                            nativa.getContentType(), leer(nativa.getInputStream())));
                }
            } catch (Exception fallo) {
                throw new HttpException(400, "no se pudo leer el multipart", fallo);
            }
            partes = Collections.unmodifiableList(partes);
        }
        return partes;
    }

    @Override
    public Part part(String nombre) {
        for (Part parte : parts()) {
            if (parte.name().equals(nombre)) {
                return parte;
            }
        }
        return null;
    }

    @Override
    public String field(String nombre) {
        Part encontrada = part(nombre);
        return encontrada == null ? null : encontrada.text();
    }

    @Override
    public InputStream body() {
        return new ByteArrayInputStream(bodyBytes());
    }

    @Override
    public byte[] bodyBytes() {
        if (cuerpo == null) {
            try {
                cuerpo = leer(crudo.getInputStream());
            } catch (IOException fallo) {
                throw new HttpException(400, "no se pudo leer el cuerpo", fallo);
            }
        }
        return cuerpo;
    }

    @Override
    public String bodyText() {
        String juego = crudo.getCharacterEncoding();
        return new String(bodyBytes(), juego == null
                ? StandardCharsets.UTF_8
                : java.nio.charset.Charset.forName(juego));
    }

    private static byte[] leer(InputStream entrada) throws IOException {
        try (entrada) {
            return entrada.readAllBytes();
        }
    }
}
