package cero.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public final class Health {

    private record Comprobacion(String nombre, Callable<Boolean> prueba) {
    }

    private final List<Comprobacion> comprobaciones = new ArrayList<>();
    private final long arranque = System.currentTimeMillis();
    private boolean publico;

    private Health() {
    }

    public static Health checks() {
        return new Health();
    }

    public Health publico() {
        publico = true;
        return this;
    }

    public Health ready(String nombre, Callable<Boolean> prueba) {
        comprobaciones.add(new Comprobacion(nombre, prueba));
        return this;
    }

    public Endpoint liveEndpoint() {
        return context -> Result.json(Map.of(
                "estado", "vivo",
                "activoMs", System.currentTimeMillis() - arranque));
    }

    public Endpoint readyEndpoint() {
        return context -> {
            Map<String, Object> detalle = new LinkedHashMap<>();
            boolean listo = true;
            for (Comprobacion c : comprobaciones) {
                try {
                    boolean bien = Boolean.TRUE.equals(c.prueba().call());
                    detalle.put(c.nombre(), bien ? "bien" : "mal");
                    listo &= bien;
                } catch (Exception fallo) {
                    detalle.put(c.nombre(), publico ? "mal" : "mal: " + mensaje(fallo));
                    listo = false;
                }
            }
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("estado", listo ? "listo" : "no listo");
            if (!publico) {
                cuerpo.put("comprobaciones", detalle);
            }
            return Result.json(cuerpo).status(listo ? 200 : 503);
        };
    }

    private static String mensaje(Exception fallo) {
        String texto = fallo.getMessage();
        return texto == null || texto.isBlank() ? fallo.getClass().getSimpleName() : texto;
    }
}
