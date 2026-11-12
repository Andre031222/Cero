#include <optional>
#include <string>
#include <string_view>

#include "cero/json.hpp"
#include "prueba.hpp"

using namespace std;
using namespace cero;

namespace {

PRUEBA(las_claves_salen_en_orden_estable) {
    const auto uno = Json::objeto({{"zeta", 1}, {"alfa", 2}, {"media", 3}});
    const auto otro = Json::objeto({{"media", 3}, {"zeta", 1}, {"alfa", 2}});
    COMPRUEBA(uno.escribir() == otro.escribir(), "orden estable");
    COMPRUEBA(uno.escribir() == R"({"alfa":2,"media":3,"zeta":1})", uno.escribir());
}

PRUEBA(se_escapan_los_tres_que_cierran_una_etiqueta) {
    const auto j = Json{"</script><img src=x onerror=robar()>"};
    const auto salida = j.escribir();
    COMPRUEBA(salida.find('<') == string::npos, "el menor");
    COMPRUEBA(salida.find('>') == string::npos, "el mayor");
    COMPRUEBA(Json{"a&b"}.escribir().find('&') == string::npos, "y el ampersand");
    COMPRUEBA(salida.find("\\u003c") != string::npos, "salen como escapes, no borrados");
}

PRUEBA(hay_tope_de_anidamiento) {
    const string bomba = string(5000, '[') + string(5000, ']');
    const auto r = leer(bomba);
    COMPRUEBA(!r.has_value(), "se rechaza en vez de reventar");

    const string llano = string(50, '[') + string(50, ']');
    COMPRUEBA(leer(llano).has_value(), "y lo razonable sigue pasando");
}

PRUEBA(lee_lo_que_escribe) {
    const auto original = Json::objeto({
        {"nombre", "ana"},
        {"edad", 30},
        {"activa", true},
        {"nada", Json{}},
        {"tags", Json::Lista{Json{"a"}, Json{"b"}}},
    });
    const auto vuelta = leer(original.escribir());
    COMPRUEBA(vuelta.has_value(), "se vuelve a leer");
    COMPRUEBA(vuelta && vuelta->escribir() == original.escribir(), "y da lo mismo");
    COMPRUEBA(vuelta && vuelta->get("nombre")->cadena() == "ana", "por clave");
    COMPRUEBA(vuelta && vuelta->get("edad")->entero() == 30, "los enteros no se vuelven reales");
    COMPRUEBA(vuelta && vuelta->get("activa")->booleano() == true, "y los booleanos");
    COMPRUEBA(vuelta && vuelta->get("tags")->lista()->size() == 2, "y las listas");
}

PRUEBA(un_cuerpo_mal_formado_se_rechaza_y_no_se_adivina) {
    for (string_view malo : {"{", "{\"a\"}", "{\"a\":}", "[1,]", "tru", "{\"a\":1} sobra",
                             "\"sin cerrar", "1.2.3"}) {
        COMPRUEBA(!leer(malo).has_value(), malo);
    }
}

PRUEBA(los_escapes_se_deshacen_al_leer) {
    const auto r = leer(R"({"t":"a\nb\tc\"d\\eñ"})");
    COMPRUEBA(r.has_value(), "se lee");
    const auto t = r->get("t")->cadena().value_or("");
    COMPRUEBA(t.find('\n') != string::npos && t.find('\t') != string::npos, "control");
    COMPRUEBA(t.find('"') != string::npos && t.find('\\') != string::npos, "comillas");
    COMPRUEBA(t.find("ñ") != string::npos, "y el punto de código");
}

PRUEBA(un_entero_grande_no_se_declara_entero_si_no_lo_es) {
    COMPRUEBA(Json{1.5}.entero() == nullopt, "1.5 no es un entero");
    COMPRUEBA(Json{2.0}.entero() == 2, "2.0 sí");
    COMPRUEBA(Json{"7"}.entero() == nullopt, "y una cadena no se convierte sola");
}

PRUEBA(un_numero_que_no_cabe_en_un_entero_no_lo_es) {
    COMPRUEBA(Json{0x1p63}.entero() == nullopt, "2^63 se sale del entero con signo");
    COMPRUEBA(Json{1e300}.entero() == nullopt, "y 1e300 con más razón");
    COMPRUEBA(Json{-0x1p63}.entero() == -0x7fffffffffffffffLL - 1, "-2^63 sí cabe");
}

}

PRUEBAS_MAIN
