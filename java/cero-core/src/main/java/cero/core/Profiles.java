package cero.core;

import java.util.LinkedHashSet;
import java.util.Set;

public final class Profiles {

    public static final String DEFECTO = "default";

    private final Set<String> activos;

    private Profiles(Set<String> activos) {
        this.activos = activos;
    }

    public static Profiles of(String... nombres) {
        return new Profiles(normalizar(String.join(",", nombres)));
    }

    public static Profiles from(Config config) {
        String declarado = System.getProperty("cero.profiles");
        if (declarado == null) {
            declarado = System.getenv("CERO_PROFILES");
        }
        if (declarado == null && config != null) {
            declarado = config.get("cero.profiles", null);
        }
        return new Profiles(normalizar(declarado));
    }

    public String active() {
        return activos.isEmpty() ? DEFECTO : activos.iterator().next();
    }

    public Set<String> all() {
        return activos.isEmpty() ? Set.of(DEFECTO) : Set.copyOf(activos);
    }

    public boolean is(String nombre) {
        return nombre != null && all().contains(nombre.trim().toLowerCase());
    }

    public boolean any(String... nombres) {
        for (String nombre : nombres) {
            if (is(nombre)) {
                return true;
            }
        }
        return false;
    }

    public boolean dev() {
        return any("dev", "development");
    }

    public boolean prod() {
        return any("prod", "production");
    }

    public boolean onlyIn(String nombre, Runnable accion) {
        if (!is(nombre)) {
            return false;
        }
        accion.run();
        return true;
    }

    @Override
    public String toString() {
        return String.join(",", all());
    }

    private static Set<String> normalizar(String declarado) {
        Set<String> nombres = new LinkedHashSet<>();
        if (declarado == null || declarado.isBlank()) {
            return nombres;
        }
        for (String parte : declarado.split(",")) {
            String limpio = parte.trim().toLowerCase();
            if (!limpio.isEmpty()) {
                nombres.add(limpio);
            }
        }
        return nombres;
    }
}
