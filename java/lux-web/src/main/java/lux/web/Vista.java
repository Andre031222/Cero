package lux.web;

import lux.core.Context;
import lux.core.Csrf;

import java.util.LinkedHashMap;
import java.util.Map;

/** Modelo base de cualquier página: título, quién está dentro y el token de los formularios. */
final class Vista {

    private Vista() {
    }

    static Map<String, Object> modelo(Context contexto, String titulo) {
        Map<String, Object> modelo = new LinkedHashMap<>();
        modelo.put("titulo", titulo);
        modelo.put("usuario", Autenticacion.actual(contexto));
        modelo.put("csrf", Csrf.token(contexto));
        return modelo;
    }
}
