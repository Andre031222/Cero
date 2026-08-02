package jxmvc.auth;

import java.io.Serializable;

/**
 * Identidad de un usuario autenticado, sea por correo/contraseña o por un proveedor
 * OAuth (Google). Se guarda en la sesión bajo la clave {@code "user"}; el
 * {@code AppBootstrap} la lee para resolver {@code @JxRequireAuth} y {@code @JxRequireRole}.
 */
public class AppUser implements Serializable {

    private String id;
    private String email;
    private String name;
    private String picture;
    private String provider;
    private String role;

    public AppUser() {}

    public AppUser(String id, String email, String name, String picture, String provider, String role) {
        this.id       = id;
        this.email    = email;
        this.name     = name;
        this.picture  = picture;
        this.provider = provider;
        this.role     = role;
    }

    public String getId()       { return id; }
    public String getEmail()    { return email; }
    public String getName()     { return name; }
    public String getPicture()  { return picture; }
    public String getProvider() { return provider; }
    public String getRole()     { return role; }

    public boolean hasRole(String r) { return role != null && role.equalsIgnoreCase(r); }
}
