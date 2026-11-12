#include <stdexcept>
#include <string>
#include <string_view>

#include "cero/fallo.hpp"
#include "cero/json.hpp"
#include "cero/servidor.hpp"
#include "prueba.hpp"

using namespace std;
using namespace cero;

namespace {

Peticion peticion(string_view destino) {
    Peticion p;
    p.metodo = "GET";
    p.destino = destino;
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    return p;
}

bool contiene(const string& texto, string_view trozo) { return texto.find(trozo) != string::npos; }

Servidor servidor() {
    Router r;
    (void)r.ruta("GET", "/estalla", "estalla");
    (void)r.ruta("GET", "/revienta/{id}", "revienta");
    (void)r.ruta("GET", "/no-esta", "no_esta");
    (void)r.ruta("GET", "/huerfana", "sin_accion");
    (void)r.ruta("GET", "/bien", "bien");
    (void)r.ruta("GET", "/ocupado", "ocupado");
    return Servidor{std::move(r)}
        .accion("estalla",
                [](const Contexto&) -> string {
                    throw runtime_error("la contraseña de la base es 'hunter2'");
                })
        .accion("revienta", [](const Contexto&) -> string { throw 42; })
        .accion("no_esta", [](const Contexto&) -> string {
            throw Fallo{404, "el artículo no existe"};
        })
        .accion("bien", [](const Contexto&) -> string { throw Fallo{200, "todo bien"}; })
        .accion("ocupado", [](const Contexto&) -> string {
            throw Fallo{503, "la base no contesta", Json::objeto({{"host", "db-interna:5432"}})};
        });
}

PRUEBA(rut_024_el_500_no_filtra_el_mensaje_interno) {
    const auto s = servidor();
    const auto r = s.responder(peticion("/estalla"));
    COMPRUEBA(r.estado == 500, "RUT-024");
    COMPRUEBA(!contiene(r.cuerpo, "hunter2"), "RUT-024: " + r.cuerpo);
    const auto otro = s.responder(peticion("/revienta/7"));
    COMPRUEBA(otro.estado == 500, "RUT-024: tampoco lo que no es una excepción estándar");
}

PRUEBA(rut_025_el_500_dice_que_ruta_fallo) {
    const auto s = servidor();
    COMPRUEBA(contiene(s.responder(peticion("/estalla")).cuerpo, "/estalla"), "RUT-025");
    COMPRUEBA(contiene(s.responder(peticion("/revienta/7")).cuerpo, "/revienta/{id}"),
              "RUT-025: por su patrón, que es lo que el cliente puede citar");
    COMPRUEBA(contiene(s.responder(peticion("/huerfana")).cuerpo, "/huerfana"),
              "RUT-025: también si la ruta no tiene acción");
}

PRUEBA(los_detalles_del_500_van_al_log) {
    const auto s = servidor();
    (void)s.responder(peticion("/estalla"));
    bool anotado = false;
    for (const auto& l : s.log().lineas()) anotado = anotado || contiene(l, "hunter2");
    COMPRUEBA(anotado, "lo que no ve el cliente lo ve quien opera");
}

PRUEBA(rut_026_un_estado_declarado_se_conserva) {
    const auto r = servidor().responder(peticion("/no-esta"));
    COMPRUEBA(r.estado == 404, "RUT-026");
    COMPRUEBA(contiene(r.cuerpo, "el artículo no existe"), "RUT-026");
}

PRUEBA(rut_027_el_manejador_de_error_decide) {
    const auto s = servidor().al_fallar([](const Contexto&, const Fallo& f) {
        auto r = Respuesta::json(Json::objeto({{"motivo", f.mensaje}}).escribir());
        r.estado = 409;
        return r;
    });
    const auto r = s.responder(peticion("/no-esta"));
    COMPRUEBA(r.estado == 409, "RUT-027");
    COMPRUEBA(r.cuerpo == R"({"motivo":"el artículo no existe"})", "RUT-027");
    COMPRUEBA(s.responder(peticion("/estalla")).estado == 409, "RUT-027: también sobre un 500");
}

bool en_el_log(const Servidor& s, string_view trozo) {
    for (const auto& l : s.log().lineas()) {
        if (contiene(l, trozo)) return true;
    }
    return false;
}

PRUEBA(un_fallo_con_estado_que_no_es_de_error_es_un_500) {
    const auto r = servidor().responder(peticion("/bien"));
    COMPRUEBA(r.estado == 500, "un Fallo de 200 es un error del programa");
    COMPRUEBA(!contiene(r.cuerpo, "todo bien"), r.cuerpo);
}

PRUEBA(rut_024_el_detalle_de_un_5xx_no_sale_pero_se_anota) {
    const auto s = servidor();
    const auto r = s.responder(peticion("/ocupado"));
    COMPRUEBA(r.estado == 503, "RUT-026: el estado se conserva");
    COMPRUEBA(!contiene(r.cuerpo, "db-interna") && !contiene(r.cuerpo, "no contesta"), "RUT-024: " + r.cuerpo);
    COMPRUEBA(en_el_log(s, "db-interna"), "y quien opera lo ve");
}

PRUEBA(si_al_fallar_tambien_falla_se_anotan_los_dos) {
    const auto s = servidor().al_fallar([](const Contexto&, const Fallo&) -> Respuesta {
        throw runtime_error("el manejador se rompió");
    });
    const auto r = s.responder(peticion("/estalla"));
    COMPRUEBA(r.estado == 500, "sale la regla por defecto");
    COMPRUEBA(en_el_log(s, "hunter2"), "el fallo original queda anotado");
    COMPRUEBA(en_el_log(s, "el manejador se rompió"), "y el del manejador también");
}

PRUEBA(un_middleware_que_lanza_no_tumba_el_servidor) {
    const auto s = servidor().usar([](const Contexto&, const Accion&) -> Respuesta {
        throw runtime_error("roto");
    });
    COMPRUEBA(s.responder(peticion("/no-esta")).estado == 500, "sale un 500, no un terminate");
}

}

PRUEBAS_MAIN
