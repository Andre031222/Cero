package corvo.web;

import corvo.core.Principal;

import java.util.Set;

/** Identidad de quien ha iniciado sesión, sea por contraseña o por Google. */
public record Usuario(String id, String email, String nombre, String foto, String proveedor, String rol)
        implements Principal {

    @Override
    public Set<String> roles() {
        return rol == null ? Set.of() : Set.of(rol);
    }

    @Override
    public boolean hasRole(String candidato) {
        return rol != null && rol.equalsIgnoreCase(candidato);
    }
}
