#include <string>
#include <string_view>

#include "cero/json.hpp"
#include "cero/servidor.hpp"
#include "prueba.hpp"

using namespace std;
using namespace cero;

namespace {

Peticion peticion(string_view metodo, string_view destino, string_view cuerpo = {}) {
    Peticion p;
    p.metodo = metodo;
    p.destino = destino;
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    p.cuerpo = cuerpo;
    return p;
}

Servidor ejemplo() {
    return Servidor{}
        .get("/", [](const Contexto&) { return Json::objeto({{"hola", "mundo"}}); })
        .get("/saludo/{nombre}",
             [](const Contexto& c) {
                 return Json::objeto({{"saludo", "hola, " + string{*c.variable("nombre")}}});
             })
        .post("/eco", [](const Contexto& c) { return c.cuerpo_json(); })
        .put("/x", [](const Contexto&) { return "put"; })
        .delete_("/x", [](const Contexto&) { return "delete"; })
        .patch("/x", [](const Contexto&) { return "patch"; });
}

}

PRUEBA(get_registra_ruta_y_accion_en_una_llamada) {
    const auto s = ejemplo();
    const auto r = s.responder(peticion("GET", "/"));
    COMPRUEBA(r.estado == 200, "GET / da 200");
    COMPRUEBA(r.cuerpo == R"({"hola":"mundo"})", "GET / da el objeto");
}

PRUEBA(la_variable_llega_a_la_accion) {
    const auto r = ejemplo().responder(peticion("GET", "/saludo/ana"));
    COMPRUEBA(r.cuerpo == R"({"saludo":"hola, ana"})", "el saludo lleva el nombre");
}

PRUEBA(post_devuelve_el_json_recibido) {
    const auto r = ejemplo().responder(peticion("POST", "/eco", R"({"a":[1,true]})"));
    COMPRUEBA(r.estado == 200, "POST /eco da 200");
    COMPRUEBA(r.cuerpo == R"({"a":[1,true]})", "el eco devuelve el mismo JSON");
}

PRUEBA(los_demas_verbos_tambien) {
    const auto s = ejemplo();
    COMPRUEBA(s.responder(peticion("PUT", "/x")).cuerpo == "put", "PUT");
    COMPRUEBA(s.responder(peticion("DELETE", "/x")).cuerpo == "delete", "DELETE");
    COMPRUEBA(s.responder(peticion("PATCH", "/x")).cuerpo == "patch", "PATCH");
}

PRUEBA(otro_verbo_sobre_el_mismo_camino_sigue_siendo_405) {
    const auto r = ejemplo().responder(peticion("DELETE", "/eco"));
    COMPRUEBA(r.estado == 405, "DELETE /eco da 405");
}

PRUEBA(convive_con_ruta_y_accion) {
    Router router;
    COMPRUEBA(router.ruta("GET", "/viejo", "viejo").has_value(), "la ruta se registra");
    const auto s = Servidor{std::move(router)}
                       .accion("viejo", [](const Contexto&) { return "viejo"; })
                       .get("/nuevo", [](const Contexto&) { return "nuevo"; });
    COMPRUEBA(s.responder(peticion("GET", "/viejo")).cuerpo == "viejo", "la forma larga sigue");
    COMPRUEBA(s.responder(peticion("GET", "/nuevo")).cuerpo == "nuevo", "la corta también");
}

PRUEBA(un_patron_mal_formado_hace_fallar_escuchar) {
    const auto s = Servidor{}
                       .get("/a/{", [](const Contexto&) { return "nunca"; })
                       .direccion("127.0.0.1");
    COMPRUEBA(s.escuchar(0) == 1, "escuchar devuelve 1 sin abrir el puerto");
}

PRUEBAS_MAIN
