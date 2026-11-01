#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

#include "cero/fallo.hpp"
#include "cero/json.hpp"
#include "cero/servidor.hpp"
#include "prueba.hpp"

using namespace std;

namespace {

struct Alta {
    string nombre;
    long long edad;

    static optional<Alta> de_json(const cero::Json& j) {
        const auto* nombre = j.get("nombre");
        const auto* edad = j.get("edad");
        if (nombre == nullptr || edad == nullptr || !nombre->cadena() || !edad->entero()) {
            return nullopt;
        }
        return Alta{string{*nombre->cadena()}, *edad->entero()};
    }
};

cero::Peticion peticion(string_view metodo, string_view destino, string_view cuerpo = {}) {
    cero::Peticion p;
    p.metodo = metodo;
    p.destino = destino;
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    p.cuerpo = cuerpo;
    return p;
}

optional<string> cabecera(const cero::Respuesta& r, string_view n) {
    for (const auto& [nombre, valor] : r.extra) {
        if (nombre == n) return valor;
    }
    return nullopt;
}

string octetos() {
    string todos;
    for (int b = 0; b < 256; ++b) todos += static_cast<char>(b);
    return todos;
}

cero::Router router() {
    cero::Router r;
    for (const auto& [metodo, camino, nombre] : vector<tuple<string, string, string>>{
             {"GET", "/a", "texto"},
             {"POST", "/a", "texto"},
             {"GET", "/objeto", "objeto"},
             {"GET", "/nada", "nada"},
             {"GET", "/fuera", "fuera"},
             {"GET", "/binario", "binario"},
             {"POST", "/eco", "eco"},
             {"GET", "/descarga", "descarga"},
             {"GET", "/numero/{id}", "numero"},
             {"POST", "/alta", "alta"},
             {"GET", "/agente", "agente"},
         }) {
        (void)r.ruta(metodo, camino, nombre);
    }
    return r;
}

cero::Servidor servidor() {
    return cero::Servidor{router()}
        .accion("texto", [](const cero::Contexto&) { return string{"hola"}; })
        .accion("objeto",
                [](const cero::Contexto&) { return cero::Json::objeto({{"a", 1}}); })
        .accion("nada", [](const cero::Contexto&) {})
        .accion("fuera", [](const cero::Contexto&) { return cero::Respuesta::redirigir("/a"); })
        .accion("binario", [](const cero::Contexto&) {
            auto r = cero::Respuesta::codigo(200, octetos());
            r.tipo = "application/octet-stream";
            return r;
        })
        .accion("eco", [](const cero::Contexto& c) { return c.peticion.cuerpo; })
        .accion("descarga", [](const cero::Contexto&) {
            return cero::Respuesta::descarga("x", "a\"b\r\nSet-Cookie: robada",
                                             "application/octet-stream");
        })
        .accion("numero",
                [](const cero::Contexto& c) { return to_string(c.variable_como<unsigned>("id") * 2); })
        .accion("alta",
                [](const cero::Contexto& c) {
                    const auto a = c.cuerpo_como<Alta>();
                    return a.nombre + " · " + to_string(a.edad);
                })
        .accion("agente", [](const cero::Contexto& c) {
            return string{c.cabecera("user-agent").value_or("sin agente")};
        });
}

PRUEBA(rut_014_y_015_la_variable_se_convierte_o_es_400) {
    const auto s = servidor();
    COMPRUEBA(s.responder(peticion("GET", "/numero/21")).cuerpo == "42", "RUT-014");
    COMPRUEBA(s.responder(peticion("GET", "/numero/ocho")).estado == 400, "RUT-015");
    COMPRUEBA(s.responder(peticion("GET", "/numero/-3")).estado == 400,
              "RUT-015: un negativo no es un unsigned");
    COMPRUEBA(s.responder(peticion("GET", "/numero/7x")).estado == 400,
              "RUT-015: ni lo es un número con cola");
}

PRUEBA(rut_017_el_cuerpo_json_se_vincula_a_un_tipo) {
    const auto s = servidor();
    const auto r = s.responder(peticion("POST", "/alta", R"({"nombre":"ana","edad":30})"));
    COMPRUEBA(r.estado == 200 && r.cuerpo == "ana · 30", "RUT-017");
    COMPRUEBA(s.responder(peticion("POST", "/alta", "{no")).estado == 400,
              "RUT-017: uno mal formado es 400, no 500");
    COMPRUEBA(s.responder(peticion("POST", "/alta", R"({"nombre":"ana"})")).estado == 422,
              "RUT-017: y uno que se entiende pero no tiene la forma, 422");
}

PRUEBA(rut_018_una_cabecera_ausente_no_es_un_fallo) {
    const auto s = servidor();
    const auto sin = s.responder(peticion("GET", "/agente"));
    COMPRUEBA(sin.estado == 200 && sin.cuerpo == "sin agente", "RUT-018");
    auto con = peticion("GET", "/agente");
    con.cabeceras.emplace("user-agent", "curl");
    COMPRUEBA(s.responder(con).cuerpo == "curl", "RUT-018: y la presente se lee");
}

PRUEBA(rut_019_a_021_las_formas_de_responder) {
    const auto s = servidor();
    const auto texto = s.responder(peticion("GET", "/a"));
    COMPRUEBA(texto.tipo.starts_with("text/plain") && texto.cuerpo == "hola", "RUT-019");
    const auto objeto = s.responder(peticion("GET", "/objeto"));
    COMPRUEBA(objeto.tipo.starts_with("application/json") && objeto.cuerpo == R"({"a":1})",
              "RUT-019");
    const auto nada = s.responder(peticion("GET", "/nada"));
    COMPRUEBA(nada.estado == 204 && nada.cuerpo.empty(), "RUT-020");
    const auto fuera = s.responder(peticion("GET", "/fuera"));
    COMPRUEBA(fuera.estado == 302, "RUT-021");
    COMPRUEBA(cabecera(fuera, "Location") == "/a", "RUT-021");
}

PRUEBA(rut_022_el_nombre_de_la_descarga_se_sanea) {
    const auto r = servidor().responder(peticion("GET", "/descarga"));
    const auto disposicion = cabecera(r, "Content-Disposition");
    COMPRUEBA(disposicion.has_value(), "RUT-022");
    COMPRUEBA(disposicion && disposicion->find_first_of("\r\n") == string::npos, "RUT-022");
    COMPRUEBA(disposicion && disposicion->find('"', 22) == disposicion->size() - 1,
              "RUT-022: la comilla del nombre no cierra antes de tiempo");
    COMPRUEBA(!cabecera(r, "Set-Cookie").has_value(), "RUT-022: y no se coló ninguna cookie");
}

PRUEBA(rut_023_el_binario_llega_entero) {
    const auto s = servidor();
    COMPRUEBA(s.responder(peticion("GET", "/binario")).cuerpo == octetos(), "RUT-023");
    COMPRUEBA(s.responder(peticion("POST", "/eco", octetos())).cuerpo == octetos(),
              "RUT-023: y de subida también");
}

PRUEBA(rut_028_el_middleware_envuelve_en_orden) {
    vector<string> traza;
    const auto s = servidor()
                       .usar([&traza](const cero::Contexto& c, const cero::Accion& siguiente) {
                           traza.push_back("entra 1");
                           auto r = siguiente(c);
                           traza.push_back("sale 1");
                           return r;
                       })
                       .usar([&traza](const cero::Contexto& c, const cero::Accion& siguiente) {
                           traza.push_back("entra 2");
                           auto r = siguiente(c);
                           traza.push_back("sale 2");
                           return r;
                       });
    (void)s.responder(peticion("GET", "/a"));
    COMPRUEBA(traza == vector<string>{"entra 1", "entra 2", "sale 2", "sale 1"}, "RUT-028");
}

PRUEBA(rut_029_el_middleware_ve_tambien_los_404) {
    int vistos = 0;
    const auto s = servidor().usar([&vistos](const cero::Contexto& c, const cero::Accion& siguiente) {
        ++vistos;
        return siguiente(c).cabecera("X-Paso-Por-Aqui", "si");
    });
    const auto r = s.responder(peticion("GET", "/no-hay-nada"));
    COMPRUEBA(r.estado == 404, "RUT-029");
    COMPRUEBA(vistos == 1, "RUT-029");
    COMPRUEBA(cabecera(r, "X-Paso-Por-Aqui") == "si", "RUT-029");
}

PRUEBA(rut_030_y_031_el_manejador_por_defecto_no_se_come_el_405) {
    const auto s = servidor()
                       .usar([](const cero::Contexto& c, const cero::Accion& siguiente) {
                           return siguiente(c).cabecera("X-Marca", "puesta");
                       })
                       .por_defecto([](const cero::Contexto&) {
                           return cero::Respuesta::html("<!doctype html><title>spa</title>");
                       });

    const auto spa = s.responder(peticion("GET", "/una/ruta/del/cliente"));
    COMPRUEBA(spa.estado == 200, "RUT-030");
    COMPRUEBA(spa.cuerpo.find("spa") != string::npos, "RUT-030");
    COMPRUEBA(cabecera(spa, "X-Marca") == "puesta", "RUT-030");

    const auto mal = s.responder(peticion("DELETE", "/a"));
    COMPRUEBA(mal.estado == 405, "RUT-031");
    COMPRUEBA(cabecera(mal, "Allow") == "GET, HEAD, POST", "RUT-031");
}

}

PRUEBAS_MAIN
