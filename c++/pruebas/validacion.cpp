#include <string>
#include <string_view>

#include "cero/json.hpp"
#include "cero/servidor.hpp"
#include "cero/validacion.hpp"
#include "prueba.hpp"

using namespace std;
using namespace cero;

namespace {

Peticion alta(string_view cuerpo) {
    Peticion p;
    p.metodo = "POST";
    p.destino = "/usuarios";
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    p.cuerpo = cuerpo;
    return p;
}

Servidor servidor() {
    Router r;
    (void)r.ruta("POST", "/usuarios", "alta");
    return Servidor{std::move(r)}.accion("alta", [](const Contexto& c) {
        return validar(c.cuerpo_json(), {
                                                  {"nombre", Obligatorio{}},
                                                  {"nombre", Texto{2, 20}},
                                                  {"edad", Entero{0, 150}},
                                              });
    });
}

Json leido(const Respuesta& r) { return leer(r.cuerpo).value_or(Json{}); }

PRUEBA(seg_027_un_cuerpo_valido_llega_sin_alterar) {
    const string cuerpo = R"({"edad":30,"extra":[1,"dos"],"nombre":"  Ana  "})";
    const auto r = servidor().responder(alta(cuerpo));
    COMPRUEBA(r.estado == 200, "SEG-027");
    COMPRUEBA(leido(r) == leer(cuerpo).value_or(Json{}), "SEG-027: " + r.cuerpo);
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

PRUEBA(seg_028_el_utf8_roto_no_burla_el_maximo) {
    const auto r = servidor().responder(alta("{\"nombre\":\"AB" + string(1 << 20, '\x80') + "\"}"));
    COMPRUEBA(r.estado == 422, "SEG-028: un mega de octetos sueltos no son dos caracteres");
    const auto detalle = leido(r);
    const auto* campos = detalle.get("campos");
    COMPRUEBA(campos && campos->get("nombre") &&
                  campos->get("nombre")->cadena() == "no es UTF-8 válido",
              "SEG-028: y se dice por qué");
}

PRUEBA(el_utf8_mal_formado_se_rechaza_en_todas_sus_formas) {
    for (const string malo : {"A\xc3", "\xc0\xaf", "\xed\xa0\x80", "\xf4\x90\x80\x80", "\xff"}) {
        const auto r = servidor().responder(alta("{\"nombre\":\"Ana" + malo + "\"}"));
        COMPRUEBA(r.estado == 422, "corta, larga, sustituto, fuera de rango o imposible");
    }
}

Servidor solo_obligatorio() {
    Router r;
    (void)r.ruta("POST", "/usuarios", "alta");
    return Servidor{std::move(r)}.accion("alta", [](const Contexto& c) {
        return validar(c.cuerpo_json(), {{"apodo", Obligatorio{}}});
    });
}

PRUEBA(seg_028_obligatorio_no_se_conforma_con_un_null_ni_con_algo_vacio) {
    for (const string vacio : {R"({"apodo":null})", R"({"apodo":""})", R"({"apodo":[]})", R"({"apodo":{}})"}) {
        const auto r = solo_obligatorio().responder(alta(vacio));
        COMPRUEBA(r.estado == 422, "SEG-028: " + vacio);
    }
    COMPRUEBA(solo_obligatorio().responder(alta(R"({"apodo":"ana"})")).estado == 200,
              "y lo que tiene valor pasa");
}

PRUEBA(el_tipo_lo_dicen_texto_y_entero_aunque_haya_obligatorio) {
    const auto r = servidor().responder(alta(R"({"nombre":5})"));
    const auto detalle = leido(r);
    const auto* campos = detalle.get("campos");
    COMPRUEBA(r.estado == 422 && campos && campos->get("nombre") &&
                  campos->get("nombre")->cadena() == "tiene que ser texto",
              r.cuerpo);
}

PRUEBA(un_cuerpo_que_no_es_json_es_400_y_no_422) {
    COMPRUEBA(servidor().responder(alta("{no")).estado == 400, "no se entendió, así que 400");
}

}

PRUEBAS_MAIN
