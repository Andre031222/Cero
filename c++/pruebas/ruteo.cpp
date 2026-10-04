// Una prueba por requisito de `spec/ruteo.md`, citándolo.
//
// Desde el primer hito y en `pruebas/`, no citados en el código: en Rust los 35 requisitos de este
// bloque figuraron cubiertos durante once hitos por estar citados junto a lo que los implementa,
// que es justo donde citarlos no demuestra nada.

#include "cero/ruta.hpp"
#include "prueba.hpp"

namespace {

cero::Patron patron(std::string_view crudo) { return *cero::Patron::nuevo(crudo); }

std::string atiende(const cero::Router& r, std::string_view metodo, std::string_view camino) {
    const auto res = r.resolver(metodo, camino);
    if (const auto* e = std::get_if<cero::Encontrada>(&res)) return e->nombre;
    if (std::holds_alternative<cero::VerboNoPermitido>(res)) return "405";
    return "404";
}

std::string variable(const cero::Patron& p, std::string_view camino, std::string_view nombre) {
    const auto c = p.casa(camino);
    if (!c) return "<no casa>";
    const auto i = c->find(nombre);
    return i == c->end() ? "<no está>" : i->second;
}

PRUEBA(rut_001_el_numero_de_segmentos_es_exacto) {
    const auto p = patron("/a/{id}");
    COMPRUEBA(p.casa("/a/7").has_value(), "RUT-001");
    COMPRUEBA(!p.casa("/a").has_value(), "RUT-001: uno menos no casa");
    COMPRUEBA(!p.casa("/a/7/b").has_value(), "RUT-001: uno más tampoco");
}

PRUEBA(rut_002_y_003_las_variables_se_recuperan_por_nombre) {
    const auto p = patron("/{seccion}/{id}/{hoja}");
    COMPRUEBA(variable(p, "/foros/7/respuestas", "seccion") == "foros", "RUT-002");
    COMPRUEBA(variable(p, "/foros/7/respuestas", "id") == "7", "RUT-003");
    COMPRUEBA(variable(p, "/foros/7/respuestas", "hoja") == "respuestas", "RUT-003");
}

PRUEBA(rut_004_la_barra_final_se_normaliza) {
    const auto p = patron("/a/{id}");
    COMPRUEBA(p.casa("/a/7") == p.casa("/a/7/"), "RUT-004");
}

PRUEBA(rut_005_el_comodin_captura_el_resto) {
    const auto p = patron("/estaticos/*");
    COMPRUEBA(variable(p, "/estaticos/a.css", "*") == "a.css", "RUT-005");
    COMPRUEBA(variable(p, "/estaticos/css/tema/a.css", "*") == "css/tema/a.css", "RUT-005");
}

PRUEBA(rut_006_y_007_un_patron_malo_no_llega_a_existir) {
    COMPRUEBA(!cero::Patron::nuevo("/a/*/b").has_value(), "RUT-006");
    COMPRUEBA(!cero::Patron::nuevo("/a/{id").has_value(), "RUT-007");
    COMPRUEBA(!cero::Patron::nuevo("/a/id}").has_value(), "RUT-007: y sin abrir");
    COMPRUEBA(!cero::Patron::nuevo("/a/{}").has_value(), "RUT-007: y sin nombre");
}

PRUEBA(rut_008_el_literal_gana_al_variable) {
    cero::Router r;
    COMPRUEBA(r.ruta("GET", "/usuarios/{id}", "ver").has_value(), "se registra");
    COMPRUEBA(r.ruta("GET", "/usuarios/nuevo", "alta").has_value(), "se registra");
    COMPRUEBA(atiende(r, "GET", "/usuarios/nuevo") == "alta", "RUT-008");
    COMPRUEBA(atiende(r, "GET", "/usuarios/7") == "ver", "y la variable sigue atendiendo lo suyo");
}

PRUEBA(rut_009_a_011_no_hay_ruta_no_es_verbo_equivocado) {
    cero::Router r;
    (void)r.ruta("GET", "/a", "ver");
    COMPRUEBA(atiende(r, "GET", "/no-existe") == "404", "RUT-009");
    COMPRUEBA(atiende(r, "POST", "/a") == "405", "RUT-009");

    const auto res = r.resolver("POST", "/a");
    const auto* mal = std::get_if<cero::VerboNoPermitido>(&res);
    const std::vector<std::string> esperados{"GET", "HEAD"};
    COMPRUEBA(mal && mal->verbos == esperados, "RUT-010");
    COMPRUEBA(atiende(r, "HEAD", "/a") == "ver", "RUT-011");
}

PRUEBA(el_patron_que_atendio_se_puede_nombrar) {
    // OBS-014 lo necesita: sin esto, `/usuarios/{id}` genera una serie de métricas por
    // identificador y el panel se llena de líneas de una sola petición.
    cero::Router r;
    (void)r.ruta("GET", "/usuarios/{id}", "ver");
    COMPRUEBA(r.patron_de("GET", "/usuarios/7") == "/usuarios/{id}", "OBS-014");
    COMPRUEBA(!r.patron_de("GET", "/nada").has_value(), "y lo que no casa no tiene patrón");
}

}  // namespace

PRUEBAS_MAIN
