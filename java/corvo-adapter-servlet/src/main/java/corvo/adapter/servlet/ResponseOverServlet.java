package corvo.adapter.servlet;

import jakarta.servlet.http.HttpServletResponse;
import corvo.http.Cookie;
import corvo.http.Headers;
import corvo.http.HttpException;
import corvo.http.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class ResponseOverServlet implements Response {

    private final HttpServletResponse crudo;
    private final Headers cabeceras = new Headers();

    private int estado = 200;
    private boolean enviada;
    private OutputStream flujo;

    ResponseOverServlet(HttpServletResponse crudo) {
        this.crudo = crudo;
    }

    @Override
    public Response status(int codigo) {
        requerirAbierta();
        estado = codigo;
        return this;
    }

    @Override
    public int status() {
        return estado;
    }

    @Override
    public Headers headers() {
        return cabeceras;
    }

    @Override
    public Response header(String nombre, String valor) {
        requerirAbierta();
        rechazarControl(nombre, valor);
        cabeceras.set(nombre, valor);
        return this;
    }

    @Override
    public Response type(String contentType) {
        return header("Content-Type", contentType);
    }

    @Override
    public Response cookie(Cookie galleta) {
        requerirAbierta();
        String codificada = encode(galleta);
        rechazarControl("Set-Cookie", codificada);
        cabeceras.add("Set-Cookie", codificada);
        return this;
    }

    @Override
    public void send(byte[] cuerpo) {
        volcar(cuerpo);
    }

    @Override
    public void send(String cuerpo) {
        volcar(cuerpo.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void text(String cuerpo) {
        tipoSiFalta("text/plain; charset=utf-8");
        send(cuerpo);
    }

    @Override
    public void html(String cuerpo) {
        tipoSiFalta("text/html; charset=utf-8");
        send(cuerpo);
    }

    @Override
    public void json(String cuerpo) {
        tipoSiFalta("application/json");
        send(cuerpo);
    }

    @Override
    public void redirect(String destino) {
        if (estado == 200) {
            estado = 302;
        }
        header("Location", destino);
        volcar(new byte[0]);
    }

    @Override
    public void redirectExternal(String destino) {
        redirect(destino);
    }

    @Override
    public OutputStream stream() {
        requerirAbierta();
        enviada = true;
        aplicarCabeceras(-1);
        try {
            flujo = crudo.getOutputStream();
            return flujo;
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
    }

    @Override
    public corvo.http.Duplex switchProtocols() {
        // El socket lo tiene el contenedor. Quien despliegue en Tomcat y necesite WebSocket usa
        // el de Tomcat; sobre lux-http la misma aplicación lo tiene sin nada extra.
        throw new UnsupportedOperationException(
                "cambiar de protocolo no se puede desde el adaptador de servlet");
    }

    @Override
    public boolean committed() {
        return enviada || crudo.isCommitted();
    }

    private void volcar(byte[] cuerpo) {
        requerirAbierta();
        enviada = true;
        aplicarCabeceras(cuerpo.length);
        try {
            if (cuerpo.length > 0) {
                crudo.getOutputStream().write(cuerpo);
            }
            crudo.getOutputStream().flush();
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
    }

    private void aplicarCabeceras(int longitud) {
        crudo.setStatus(estado);
        for (int i = 0; i < cabeceras.size(); i++) {
            String nombre = cabeceras.name(i);
            String valor = cabeceras.value(i);
            if (nombre.equalsIgnoreCase("Content-Type")) {
                crudo.setContentType(valor);
            } else if (nombre.equalsIgnoreCase("Set-Cookie")) {
                crudo.addHeader(nombre, valor);
            } else {
                crudo.setHeader(nombre, valor);
            }
        }
        if (longitud >= 0) {
            crudo.setContentLength(longitud);
        }
    }

    private void tipoSiFalta(String contentType) {
        if (!cabeceras.has("Content-Type")) {
            cabeceras.set("Content-Type", contentType);
        }
    }

    private void requerirAbierta() {
        if (enviada) {
            throw new IllegalStateException("respuesta ya enviada");
        }
    }

    private static String encode(Cookie galleta) {
        StringBuilder texto = new StringBuilder(64);
        texto.append(galleta.name()).append('=').append(galleta.value());
        if (galleta.path() != null) {
            texto.append("; Path=").append(galleta.path());
        }
        if (galleta.domain() != null) {
            texto.append("; Domain=").append(galleta.domain());
        }
        if (galleta.hasMaxAge()) {
            texto.append("; Max-Age=").append(galleta.maxAgeSeconds());
        }
        if (galleta.secure()) {
            texto.append("; Secure");
        }
        if (galleta.httpOnly()) {
            texto.append("; HttpOnly");
        }
        if (galleta.sameSite() != null) {
            texto.append("; SameSite=").append(galleta.sameSite());
        }
        return texto.toString();
    }

    private static void rechazarControl(String nombre, String valor) {
        if (tieneControl(nombre) || tieneControl(valor)) {
            throw new HttpException(500, "cabecera con caracteres de control: " + nombre);
        }
    }

    private static boolean tieneControl(String texto) {
        if (texto == null) {
            return false;
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
