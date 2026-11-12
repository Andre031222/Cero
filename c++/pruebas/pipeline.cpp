#include <chrono>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "cero/servidor.hpp"
#include "prueba.hpp"

using namespace std;
using namespace cero;

namespace {

Peticion peticion(string_view metodo, string_view destino) {
    Peticion p;
    p.metodo = metodo;
    p.destino = destino;
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    return p;
}

optional<string> cabecera(const Respuesta& r, string_view n) {
    for (const auto& [nombre, valor] : r.extra) {
        if (nombre == n) return valor;
    }
    return nullopt;
}

Router router() {
    Router r;
    (void)r.ruta("GET", "/a", "texto");
    (void)r.ruta("POST", "/a", "texto");
    (void)r.ruta("GET", "/estalla", "estalla");
    (void)r.ruta("GET", "/entrar", "entrar");
    (void)r.ruta("GET", "/formulario", "formulario");
    (void)r.ruta("POST", "/guardar", "guardar");
    (void)r.ruta("GET", "/busca", "busca");
    (void)r.ruta("GET", "/numero/{id}", "numero");
    return r;
}

Servidor montado() {
    return Servidor{router()}
        .accion("texto", [](const Contexto&) { return Respuesta::texto("hola"); })
        .accion("estalla", [](const Contexto&) { return Respuesta::codigo(500, "roto"); })
        .accion("entrar",
                [](const Contexto& c) {
                    const auto g = c.abrir_sesion();
                    if (!g) return Respuesta::codigo(500);
                    const auto tomado = g->tomar();
                    g->sesion->poner("usuario", "ana");
                    return Respuesta::texto("dentro");
                })
        .accion("formulario",
                [](const Contexto& c) {
                    return Respuesta::texto(c.token_csrf().value_or(""));
                })
        .accion("guardar", [](const Contexto&) { return Respuesta::texto("guardado"); })
        .accion("busca", [](const Contexto& c) { return Respuesta::texto(c.consulta_o("q", "")); })
        .accion("numero", [](const Contexto& c) {
            return Respuesta::texto(string{c.variable("id").value_or("")});
        });
}

PRUEBA(rut_029_las_cabeceras_de_seguridad_van_tambien_en_el_404) {
    const auto s = montado();
    for (const char* camino : {"/a", "/no-existe"}) {
        const auto r = s.responder(peticion("GET", camino));
        COMPRUEBA(cabecera(r, "X-Content-Type-Options") == "nosniff", "SEG-001 en toda respuesta");
    }
}

PRUEBA(rut_012_y_013_el_404_y_el_405_de_punta_a_punta) {
    const auto s = montado();
    COMPRUEBA(s.responder(peticion("GET", "/no-existe")).estado == 404, "RUT-012");
    const auto r = s.responder(peticion("DELETE", "/a"));
    COMPRUEBA(r.estado == 405, "RUT-013");
    COMPRUEBA(cabecera(r, "Allow") == "GET, HEAD, POST", "RUT-013");
}

PRUEBA(el_contexto_llega_con_lo_de_la_peticion) {
    const auto s = montado();
    COMPRUEBA(s.responder(peticion("GET", "/numero/42")).cuerpo == "42", "RUT-002");
    COMPRUEBA(s.responder(peticion("GET", "/busca?q=gatos")).cuerpo == "gatos", "RUT-016");
    COMPRUEBA(s.responder(peticion("GET", "/busca")).cuerpo.empty(),
              "RUT-016: ausente y vacío no son lo mismo, y aquí toca el defecto");
}

PRUEBA(el_csrf_no_puede_tapar_el_405) {
    const auto s = Servidor{router()}
                       .csrf({})
                       .accion("texto", [](const Contexto&) {
                           return Respuesta::texto("hola");
                       });
    COMPRUEBA(s.responder(peticion("DELETE", "/a")).estado == 405, "RUT-009 sobre CSRF activo");
    COMPRUEBA(s.responder(peticion("GET", "/no-existe")).estado == 404, "RUT-009");
    COMPRUEBA(s.responder(peticion("POST", "/a")).estado == 403, "y el que sí enruta, 403");
}

PRUEBA(ses_010_la_sesion_abierta_en_la_accion_emite_su_cookie) {
    const auto s = montado();
    const auto r = s.responder(peticion("GET", "/entrar"));
    const auto cookie = cabecera(r, "Set-Cookie");
    COMPRUEBA(cookie.has_value(), "SES-010");
    COMPRUEBA(cookie && cookie->find("cero_sid=") != string::npos, "SES-010");
    COMPRUEBA(cookie && cookie->find("HttpOnly") != string::npos, "SES-009");
}

PRUEBA(ses_008_la_sesion_que_llega_no_reemite_la_cookie) {
    const auto s = montado();
    const auto cookie = *cabecera(s.responder(peticion("GET", "/entrar")), "Set-Cookie");
    const auto id = cookie.substr(0, cookie.find(';'));

    auto segunda = peticion("GET", "/a");
    segunda.cabeceras.emplace("cookie", id);
    COMPRUEBA(!cabecera(s.responder(segunda), "Set-Cookie").has_value(), "SES-008");
}

PRUEBA(seg_017_el_token_se_emite_y_lo_ata_una_sesion) {
    const auto s = Servidor{router()}
                       .csrf({})
                       .accion("formulario",
                               [](const Contexto& c) {
                                   return Respuesta::texto(c.token_csrf().value_or(""));
                               })
                       .accion("guardar", [](const Contexto&) {
                           return Respuesta::texto("guardado");
                       });

    const auto formulario = s.responder(peticion("GET", "/formulario"));
    const auto token = formulario.cuerpo;
    COMPRUEBA(token.size() >= 40, "SEG-017: hay token, y no es simbólico");
    const auto cookie = cabecera(formulario, "Set-Cookie");
    COMPRUEBA(cookie.has_value(), "SEG-017: y una sesión que lo ate");
    const auto id = cookie->substr(0, cookie->find(';'));

    auto solo_token = peticion("POST", "/guardar");
    solo_token.cabeceras.emplace("x-csrf-token", token);
    COMPRUEBA(s.responder(solo_token).estado == 403, "SEG-016: sin la sesión no vale");

    auto solo_sesion = peticion("POST", "/guardar");
    solo_sesion.cabeceras.emplace("cookie", id);
    COMPRUEBA(s.responder(solo_sesion).estado == 403, "SEG-016: ni sin el token");

    auto ambos = peticion("POST", "/guardar");
    ambos.cabeceras.emplace("cookie", id);
    ambos.cabeceras.emplace("x-csrf-token", token);
    COMPRUEBA(s.responder(ambos).estado == 200, "SEG-018");

    auto por_campo = peticion("POST", "/guardar");
    por_campo.cabeceras.emplace("cookie", id);
    por_campo.cuerpo = "_csrf=" + token;
    COMPRUEBA(s.responder(por_campo).estado == 200, "SEG-018: y por campo");
}

PRUEBA(obs_012_el_log_lleva_el_estado_que_de_verdad_salio) {
    const auto s = montado();
    COMPRUEBA(s.responder(peticion("GET", "/estalla")).estado == 500, "sale un 500");
    bool encontrada = false;
    for (const auto& l : s.log().lineas()) {
        if (l.find("/estalla") != string::npos && l.find("500") != string::npos) {
            encontrada = true;
        }
    }
    COMPRUEBA(encontrada, "OBS-012");
}

PRUEBA(obs_017_y_023_lo_declarado_ignorado_no_cuenta_ni_se_registra) {
    const auto s = montado().sin_registrar({"/a"});
    (void)s.responder(peticion("GET", "/a"));
    (void)s.responder(peticion("GET", "/busca"));
    COMPRUEBA(s.metricas().peticiones("/a") == 0, "OBS-017");
    COMPRUEBA(s.metricas().peticiones("/busca") == 1, "OBS-017: y lo demás sí");

    bool registrada = false;
    for (const auto& l : s.log().lineas()) {
        if (l.find("GET /a ") != string::npos) registrada = true;
    }
    COMPRUEBA(!registrada, "OBS-023");
}

PRUEBA(obs_014_las_metricas_agrupan_por_patron) {
    const auto s = montado();
    for (int i = 0; i < 50; ++i) (void)s.responder(peticion("GET", "cero" + to_string(i)));
    for (int i = 0; i < 50; ++i) {
        (void)s.responder(peticion("GET", "/numero/" + to_string(i)));
    }
    COMPRUEBA(s.metricas().peticiones("/numero/{id}") == 50, "OBS-014");
}

PRUEBA(el_preflight_ajeno_corta_y_el_permitido_responde_204) {
    const auto s = montado().cors(Cors{{"https://mio.dev"}});
    auto ajeno = peticion("OPTIONS", "/a");
    ajeno.cabeceras.emplace("origin", "https://otro.dev");
    COMPRUEBA(s.responder(ajeno).estado == 403, "SEG-013");

    auto bueno = peticion("OPTIONS", "/a");
    bueno.cabeceras.emplace("origin", "https://mio.dev");
    const auto r = s.responder(bueno);
    COMPRUEBA(r.estado == 204, "SEG-012");
    COMPRUEBA(cabecera(r, "X-Content-Type-Options") == "nosniff",
              "y hasta el preflight lleva las cabeceras de seguridad");
}

PRUEBA(al_pasarse_del_limite_sale_un_429_con_retry_after) {
    const auto s = montado().limite(2, 60s);
    (void)s.responder(peticion("GET", "/a"), "1.2.3.4");
    (void)s.responder(peticion("GET", "/a"), "1.2.3.4");
    const auto r = s.responder(peticion("GET", "/a"), "1.2.3.4");
    COMPRUEBA(r.estado == 429, "SEG-020");
    COMPRUEBA(cabecera(r, "Retry-After").has_value(), "SEG-020");
    COMPRUEBA(s.responder(peticion("GET", "/a"), "5.6.7.8").estado == 200, "y otro cliente pasa");
}

PRUEBA(la_salud_se_atiende_antes_que_todo_lo_demas) {
    auto estado = make_shared<Salud>();
    estado->comprobacion("base", [] { return Comprobado::va(); });
    const auto s = montado().salud(estado).limite(1, 60s);
    (void)s.responder(peticion("GET", "/a"), "1.2.3.4");
    (void)s.responder(peticion("GET", "/a"), "1.2.3.4");
    COMPRUEBA(s.responder(peticion("GET", "/cero/vivo"), "1.2.3.4").estado == 200, "OBS-001");
    COMPRUEBA(s.responder(peticion("GET", "/cero/listo"), "1.2.3.4").estado == 200, "OBS-002");
}

PRUEBA(ses_012_dos_servidores_con_el_mismo_almacen_ven_lo_mismo) {
    const auto compartido = make_shared<Almacen>(600s, nullopt);
    const auto una = montado().sesiones(compartido);
    const auto otra = Servidor{router()}
                          .sesiones(compartido)
                          .accion("texto", [](const Contexto& c) {
                              const auto& g = c.sesion();
                              if (!g) return Respuesta::texto("nadie");
                              const auto tomado = g->tomar();
                              return Respuesta::texto(
                                  string{g->sesion->leer("usuario").value_or("nadie")});
                          });

    const auto cookie = *cabecera(una.responder(peticion("GET", "/entrar")), "Set-Cookie");
    auto segunda = peticion("GET", "/a");
    segunda.cabeceras.emplace("cookie", cookie.substr(0, cookie.find(';')));
    COMPRUEBA(otra.responder(segunda).cuerpo == "ana", "SES-012");
}

}

PRUEBAS_MAIN
