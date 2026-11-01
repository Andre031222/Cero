#include <string>
#include <string_view>

#include "cero/json.hpp"
#include "cero/servidor.hpp"
#include "cero/validacion.hpp"
#include "prueba.hpp"

using namespace std;

namespace {

cero::Peticion alta(string_view cuerpo) {
    cero::Peticion p;
    p.metodo = "POST";
    p.destino = "/usuarios";
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    p.cuerpo = cuerpo;
    return p;
}

cero::Servidor servidor() {
    cero::Router r;
    (void)r.ruta("POST", "/usuarios", "alta");
    return cero::Servidor{std::move(r)}.accion("alta", [](const cero::Contexto& c) {
        return cero::validar(c.cuerpo_json(), {
                                                  {"nombre", cero::Obligatorio{}},
                                                  {"nombre", cero::Texto{2, 20}},
                                                  {"edad", cero::Entero{0, 150}},
                                              });
    });
}

cero::Json leido(const cero::Respuesta& r) { return cero::leer(r.cuerpo).value_or(cero::Json{}); }

PRUEBA(seg_027_un_cuerpo_valido_llega_sin_alterar) {
    const string cuerpo = R"({"edad":30,"extra":[1,"dos"],"nombre":"  Ana  "})";
    const auto r = servidor().responder(alta(cuerpo));
    COMPRUEBA(r.estado == 200, "SEG-027");
    COMPRUEBA(leido(r) == cero::leer(cuerpo).value_or(cero::Json{}), "SEG-027: " + r.cuerpo);
    COMPRUEBA(leido(r).get("nombre") && leido(r).get("nombre")->cadena() == "  Ana  ",
              "SEG-027: validar no es normalizar");
}

PRUEBA(seg_028_un_cuerpo_invalido_es_422_con_el_campo_y_el_motivo) {
    const auto r = servidor().responder(alta(R"({"nombre":"A","edad":200})"));
    COMPRUEBA(r.estado == 422, "SEG-028: 422, no 400");
    COMPRUEBA(r.tipo.starts_with("application/json"), "SEG-028");
    const auto detalle = leido(r);
    const auto* campos = detalle.get("campos");
    COMPRUEBA(campos != nullptr, "SEG-028: " + r.cuerpo);
    const auto motivo = [campos](string_view campo) {
        const auto* m = campos == nullptr ? nullptr : campos->get(campo);
        return m == nullptr ? string{} : string{m->cadena().value_or("")};
    };
    COMPRUEBA(motivo("nombre") == "no llega a 2 caracteres", "SEG-028: " + r.cuerpo);
    COMPRUEBA(motivo("edad") == "es mayor que 150", "SEG-028: " + r.cuerpo);
}

PRUEBA(seg_028_lo_que_falta_y_el_tipo_equivocado_tambien_se_dicen) {
    const auto r = servidor().responder(alta(R"({"edad":"treinta"})"));
    COMPRUEBA(r.estado == 422, "SEG-028");
    const auto detalle = leido(r);
    const auto* campos = detalle.get("campos");
    COMPRUEBA(campos && campos->get("nombre") && campos->get("nombre")->cadena() == "es obligatorio",
              "SEG-028: " + r.cuerpo);
    COMPRUEBA(campos && campos->get("edad") &&
                  campos->get("edad")->cadena() == "tiene que ser un número entero",
              "SEG-028: " + r.cuerpo);
}

PRUEBA(los_caracteres_se_cuentan_y_no_los_octetos) {
    const auto r = servidor().responder(alta(R"({"nombre":"Ñu"})"));
    COMPRUEBA(r.estado == 200, "«Ñu» son dos caracteres aunque sean tres octetos: " + r.cuerpo);
}

PRUEBA(un_cuerpo_que_no_es_json_es_400_y_no_422) {
    COMPRUEBA(servidor().responder(alta("{no")).estado == 400, "no se entendió, así que 400");
}

}

PRUEBAS_MAIN
