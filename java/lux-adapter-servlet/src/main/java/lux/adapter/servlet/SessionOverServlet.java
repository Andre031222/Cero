package lux.adapter.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lux.http.Session;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class SessionOverServlet implements Session {

    private final HttpServletRequest peticion;
    private final HttpSession nativa;
    private final boolean recienCreada;

    private boolean anulada;

    SessionOverServlet(HttpServletRequest peticion, HttpSession nativa) {
        this.peticion = peticion;
        this.nativa = nativa;
        this.recienCreada = nativa.isNew();
    }

    @Override
    public String id() {
        return nativa.getId();
    }

    @Override
    public Object get(String clave) {
        requerirValida();
        return nativa.getAttribute(clave);
    }

    @Override
    public <T> T get(String clave, Class<T> tipo) {
        Object encontrado = get(clave);
        return tipo.isInstance(encontrado) ? tipo.cast(encontrado) : null;
    }

    @Override
    public void set(String clave, Object valor) {
        requerirValida();
        if (valor == null) {
            nativa.removeAttribute(clave);
        } else {
            nativa.setAttribute(clave, valor);
        }
    }

    @Override
    public void remove(String clave) {
        requerirValida();
        nativa.removeAttribute(clave);
    }

    @Override
    public Set<String> keys() {
        requerirValida();
        Set<String> claves = new HashSet<>();
        var nombres = nativa.getAttributeNames();
        while (nombres.hasMoreElements()) {
            claves.add(nombres.nextElement());
        }
        return Collections.unmodifiableSet(claves);
    }

    @Override
    public void regenerateId() {
        requerirValida();
        // El contenedor ya sabe hacerlo: cambia el identificador, conserva los atributos y emite
        // la cookie nueva. Es lo mismo que hace Sessions cuando el servidor es el de LuxCore.
        peticion.changeSessionId();
    }

    @Override
    public void invalidate() {
        if (!anulada) {
            anulada = true;
            nativa.invalidate();
        }
    }

    @Override
    public boolean valid() {
        return !anulada;
    }

    @Override
    public boolean created() {
        return recienCreada;
    }

    @Override
    public long createdAt() {
        return nativa.getCreationTime();
    }

    @Override
    public long lastAccessAt() {
        return nativa.getLastAccessedTime();
    }

    private void requerirValida() {
        if (anulada) {
            throw new IllegalStateException("sesión invalidada");
        }
    }
}
