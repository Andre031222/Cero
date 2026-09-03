package cero.web;

import cero.core.Context;
import cero.core.Csrf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        modelo.put("v", Huella.ACTUAL);
        // Para que la barra sepa marcar en qué sección estás.
        String ruta = contexto.path();
        modelo.put("ruta", ruta);

        // Bilingüe: el inglés vive bajo /en. De la ruta actual sale su pareja en el otro
        // idioma, que es lo que necesitan el conmutador y las etiquetas hreflang.
        boolean ingles = ruta.equals("/en") || ruta.startsWith("/en/");
        modelo.put("idioma", ingles ? "en" : "es");
        modelo.put("otroIdioma", ingles ? "es" : "en");
        modelo.put("etiquetaOtro", ingles ? "Español" : "English");
        modelo.put("tituloOtro", ingles
                ? "Leer esta página en español"
                : "Read this page in English");
        String otra;
        if (ingles) {
            otra = ruta.equals("/en") ? "/" : ruta.substring(3);
        } else {
            otra = ruta.equals("/") ? "/en" : "/en" + ruta;
        }
        modelo.put("rutaOtra", otra);
        modelo.put("rutaEs", ingles ? otra : ruta);
        modelo.put("rutaEn", ingles ? ruta : otra);
        modelo.put("nav", navegacion(ruta, ingles));
        modelo.put("movil", ingles
                ? List.of("Home", "Get", "Guide", "Modules")
                : List.of("Inicio", "Bajar", "Guía", "Módulos"));
        modelo.put("inicio", ingles ? "/en" : "/");
        modelo.put("base", ingles ? "/en" : "");
        return modelo;
    }

    /** La barra, en el idioma que toque, con la entrada actual ya marcada. */
    private static List<Map<String, Object>> navegacion(String ruta, boolean ingles) {
        String[][] entradas = ingles
                ? new String[][] {
                        {"/en", "Home"}, {"/en/descargas", "Downloads"},
                        {"/en/empezar", "Get started"}, {"/en/guia", "Guide"},
                        {"/en/modulos", "Modules"}, {"/en/referencia", "Reference"},
                        {"/panel", "Dashboard"}}
                : new String[][] {
                        {"/", "Inicio"}, {"/descargas", "Descargas"},
                        {"/empezar", "Empezar"}, {"/guia", "Guía"},
                        {"/modulos", "Módulos"}, {"/referencia", "Referencia"},
                        {"/panel", "Panel"}};

        List<Map<String, Object>> salida = new ArrayList<>();
        for (String[] entrada : entradas) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("href", entrada[0]);
            item.put("texto", entrada[1]);
            item.put("actual", entrada[0].equals(ruta));
            salida.add(item);
        }
        return salida;
    }
}
