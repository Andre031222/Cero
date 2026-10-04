// El JSON propio. No hay requisitos numerados para él —`spec/` no cubre todavía la forma de la
// petición y la respuesta—, así que lo que se comprueba es lo que lo hace seguro de usar.

#include <optional>
#include <string>
#include <string_view>

#include "cero/json.hpp"
#include "prueba.hpp"

namespace {

PRUEBA(las_claves_salen_en_orden_estable) {
    // Dos respuestas iguales tienen que dar los mismos octetos, o no se pueden comparar ni
    // cachear. Una tabla de dispersión no lo garantiza: el orden depende de la siembra.
    const auto uno = cero::Json::objeto({{"zeta", 1}, {"alfa", 2}, {"media", 3}});
    const auto otro = cero::Json::objeto({{"media", 3}, {"zeta", 1}, {"alfa", 2}});
    COMPRUEBA(uno.escribir() == otro.escribir(), "orden estable");
    COMPRUEBA(uno.escribir() == R"({"alfa":2,"media":3,"zeta":1})", uno.escribir());
}

PRUEBA(se_escapan_los_tres_que_cierran_una_etiqueta) {
    // Un JSON incrustado en una página que contenga `</script` cierra la etiqueta, y lo que siga
    // se ejecuta: es XSS a través de una respuesta perfectamente válida.
    const auto j = cero::Json{"</script><img src=x onerror=robar()>"};
    const auto salida = j.escribir();
    COMPRUEBA(salida.find('<') == std::string::npos, "el menor");
    COMPRUEBA(salida.find('>') == std::string::npos, "el mayor");
    COMPRUEBA(cero::Json{"a&b"}.escribir().find('&') == std::string::npos, "y el ampersand");
    COMPRUEBA(salida.find("\\u003c") != std::string::npos, "salen como escapes, no borrados");
}

PRUEBA(hay_tope_de_anidamiento) {
    // Cinco mil corchetes son diez kilobytes de cuerpo y desbordan la pila de un lector
    // recursivo: denegación de servicio con una petición pequeña y perfectamente válida.
    const std::string bomba = std::string(5000, '[') + std::string(5000, ']');
    const auto r = cero::leer(bomba);
    COMPRUEBA(!r.has_value(), "se rechaza en vez de reventar");

    const std::string llano = std::string(50, '[') + std::string(50, ']');
    COMPRUEBA(cero::leer(llano).has_value(), "y lo razonable sigue pasando");
}

PRUEBA(lee_lo_que_escribe) {
    const auto original = cero::Json::objeto({
        {"nombre", "ana"},
        {"edad", 30},
        {"activa", true},
        {"nada", cero::Json{}},
        {"tags", cero::Json::Lista{cero::Json{"a"}, cero::Json{"b"}}},
    });
    const auto vuelta = cero::leer(original.escribir());
    COMPRUEBA(vuelta.has_value(), "se vuelve a leer");
    COMPRUEBA(vuelta && vuelta->escribir() == original.escribir(), "y da lo mismo");
    COMPRUEBA(vuelta && vuelta->get("nombre")->cadena() == "ana", "por clave");
    COMPRUEBA(vuelta && vuelta->get("edad")->entero() == 30, "los enteros no se vuelven reales");
    COMPRUEBA(vuelta && vuelta->get("activa")->booleano() == true, "y los booleanos");
    COMPRUEBA(vuelta && vuelta->get("tags")->lista()->size() == 2, "y las listas");
}

PRUEBA(un_cuerpo_mal_formado_se_rechaza_y_no_se_adivina) {
    for (std::string_view malo : {"{", "{\"a\"}", "{\"a\":}", "[1,]", "tru", "{\"a\":1} sobra",
                                  "\"sin cerrar", "1.2.3"}) {
        COMPRUEBA(!cero::leer(malo).has_value(), malo);
    }
}

PRUEBA(los_escapes_se_deshacen_al_leer) {
    const auto r = cero::leer(R"({"t":"a\nb\tc\"d\\eñ"})");
    COMPRUEBA(r.has_value(), "se lee");
    const auto t = r->get("t")->cadena().value_or("");
    COMPRUEBA(t.find('\n') != std::string::npos && t.find('\t') != std::string::npos, "control");
    COMPRUEBA(t.find('"') != std::string::npos && t.find('\\') != std::string::npos, "comillas");
    COMPRUEBA(t.find("ñ") != std::string::npos, "y el punto de código");
}

PRUEBA(un_entero_grande_no_se_declara_entero_si_no_lo_es) {
    COMPRUEBA(cero::Json{1.5}.entero() == std::nullopt, "1.5 no es un entero");
    COMPRUEBA(cero::Json{2.0}.entero() == 2, "2.0 sí");
    COMPRUEBA(cero::Json{"7"}.entero() == std::nullopt, "y una cadena no se convierte sola");
}

}  // namespace

PRUEBAS_MAIN
