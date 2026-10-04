// El framework montado entero: lo que solo se ve conectando los módulos.
//
// En Rust este hito destapó dos fallos que ninguna prueba de módulo podía ver, y los dos están
// aquí comprobados desde el principio: el CSRF tapando el 405 y la sesión abierta dentro de la
// acción sin emitir su cookie.

#include <chrono>
#include <string>

#include "cero/servidor.hpp"
#include "prueba.hpp"

namespace {

using namespace std::chrono_literals;

cero::Peticion peticion(std::string_view metodo, std::string_view destino) {
    cero::Peticion p;
    p.metodo = metodo;
    p.destino = destino;
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("host", "x");
    return p;
}

std::optional<std::string> cabecera(const cero::Respuesta& r, std::string_view n) {
    for (const auto& [nombre, valor] : r.extra) {
        if (nombre == n) return valor;
    }
    return std::nullopt;
}

bool dice(const cero::Respuesta& r, std::string_view que) {
    return r.cuerpo.find(que) != std::string::npos;
}

cero::Router router() {
    cero::Router r;
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

cero::Servidor montado() {
    return cero::Servidor{router()}
        .accion("texto", [](const cero::Contexto&) { return cero::Respuesta::texto("hola"); })
        .accion("estalla", [](const cero::Contexto&) { return cero::Respuesta::codigo(500, "roto"); })
        .accion("entrar",
                [](const cero::Contexto& c) {
                    const auto g = c.abrir_sesion();
                    if (!g) return cero::Respuesta::codigo(500);
                    const auto tomado = g->tomar();
                    g->sesion->poner("usuario", "ana");
                    return cero::Respuesta::texto("dentro");
                })
        .accion("formulario",
                [](const cero::Contexto& c) {
                    return cero::Respuesta::texto(c.token_csrf().value_or(""));
                })
        .accion("guardar", [](const cero::Contexto&) { return cero::Respuesta::texto("guardado"); })
        .accion("busca",
                [](const cero::Contexto& c) { return cero::Respuesta::texto(c.consulta_o("q", "")); })
        .accion("numero", [](const cero::Contexto& c) {
            return cero::Respuesta::texto(std::string{c.variable("id").value_or("")});
        });
}

// ── Lo que el pipeline aplica a toda respuesta ──────────────────────────────

PRUEBA(las_cabeceras_de_seguridad_van_tambien_en_el_404) {
    // RUT-029 por el lado que más se incumple: un 404 sin cabeceras de seguridad deja sin
    // proteger justo las respuestas que más se provocan desde fuera.
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

// ── El fallo que destapó montar el framework en Rust ────────────────────────

PRUEBA(el_csrf_no_puede_tapar_el_405) {
    // El CSRF corría antes del ruteo, así que un verbo no admitido recibía 403 en vez de 405: la
    // respuesta atribuía el fallo a la causa equivocada, que es lo que RUT-009 prohíbe al exigir
    // distinguir 404 de 405. Con los módulos sueltos no se ve.
    const auto s = cero::Servidor{router()}
                       .csrf({})
                       .accion("texto", [](const cero::Contexto&) {
                           return cero::Respuesta::texto("hola");
                       });
    COMPRUEBA(s.responder(peticion("DELETE", "/a")).estado == 405, "RUT-009 sobre CSRF activo");
    COMPRUEBA(s.responder(peticion("GET", "/no-existe")).estado == 404, "RUT-009");
    COMPRUEBA(s.responder(peticion("POST", "/a")).estado == 403, "y el que sí enruta, 403");
}

PRUEBA(ses_010_la_sesion_abierta_en_la_accion_emite_su_cookie) {
    // `abrir_sesion()` la creaba, pero el punto que emite la cookie solo miraba la que había
    // llegado **con** la petición: el cliente abría sesión y no recibía nada.
    const auto s = montado();
    const auto r = s.responder(peticion("GET", "/entrar"));
    const auto cookie = cabecera(r, "Set-Cookie");
    COMPRUEBA(cookie.has_value(), "SES-010");
    COMPRUEBA(cookie && cookie->find("cero_sid=") != std::string::npos, "SES-010");
    COMPRUEBA(cookie && cookie->find("HttpOnly") != std::string::npos, "SES-009");
}

PRUEBA(ses_008_la_sesion_que_llega_no_reemite_la_cookie) {
    const auto s = montado();
    const auto cookie = *cabecera(s.responder(peticion("GET", "/entrar")), "Set-Cookie");
    const auto id = cookie.substr(0, cookie.find(';'));

    auto segunda = peticion("GET", "/a");
    segunda.cabeceras.emplace("cookie", id);
    COMPRUEBA(!cabecera(s.responder(segunda), "Set-Cookie").has_value(), "SES-008");
}

// ── SEG-017, el token de punta a punta ─────────────────────────────────────

PRUEBA(seg_017_el_token_se_emite_y_lo_ata_una_sesion) {
    // Sin esto el servidor sabe validar el token y no sabe darlo, así que una ruta protegida es
    // imposible de usar: ningún cliente tiene de dónde sacarlo.
    const auto s = cero::Servidor{router()}
                       .csrf({})
                       .accion("formulario",
                               [](const cero::Contexto& c) {
                                   return cero::Respuesta::texto(c.token_csrf().value_or(""));
                               })
                       .accion("guardar", [](const cero::Contexto&) {
                           return cero::Respuesta::texto("guardado");
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

    // Por campo del formulario también: solo la cabecera dejaba fuera a un formulario HTML, que
    // es justo donde el CSRF hace más falta.
    auto por_campo = peticion("POST", "/guardar");
    por_campo.cabeceras.emplace("cookie", id);
    por_campo.cuerpo = "_csrf=" + token;
    COMPRUEBA(s.responder(por_campo).estado == 200, "SEG-018: y por campo");
}

// ── Observabilidad del pipeline ────────────────────────────────────────────

PRUEBA(obs_012_el_log_lleva_el_estado_que_de_verdad_salio) {
    const auto s = montado();
    COMPRUEBA(s.responder(peticion("GET", "/estalla")).estado == 500, "sale un 500");
    bool encontrada = false;
    for (const auto& l : s.log().lineas()) {
        if (l.find("/estalla") != std::string::npos && l.find("500") != std::string::npos) {
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
        if (l.find("GET /a ") != std::string::npos) registrada = true;
    }
    COMPRUEBA(!registrada, "OBS-023");
}

PRUEBA(obs_014_las_metricas_agrupan_por_patron) {
    const auto s = montado();
    for (int i = 0; i < 50; ++i) (void)s.responder(peticion("GET", "cero" + std::to_string(i)));
    for (int i = 0; i < 50; ++i) {
        (void)s.responder(peticion("GET", "/numero/" + std::to_string(i)));
    }
    COMPRUEBA(s.metricas().peticiones("/numero/{id}") == 50, "OBS-014");
}

// ── CORS y límite, ya dentro del pipeline ──────────────────────────────────

PRUEBA(el_preflight_ajeno_corta_y_el_permitido_responde_204) {
    const auto s = montado().cors(cero::Cors{{"https://mio.dev"}});
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
    auto estado = std::make_shared<cero::Salud>();
    estado->comprobacion("base", [] { return cero::Comprobado::va(); });
    // Con el límite agotado, la salud tiene que seguir contestando: un proceso que no puede
    // atender tiene que poder decirlo, y si el límite la tapa el orquestador lo mata por sano.
    const auto s = montado().salud(estado).limite(1, 60s);
    (void)s.responder(peticion("GET", "/a"), "1.2.3.4");
    (void)s.responder(peticion("GET", "/a"), "1.2.3.4");
    COMPRUEBA(s.responder(peticion("GET", "/cero/vivo"), "1.2.3.4").estado == 200, "OBS-001");
    COMPRUEBA(s.responder(peticion("GET", "/cero/listo"), "1.2.3.4").estado == 200, "OBS-002");
}

PRUEBA(ses_012_dos_servidores_con_el_mismo_almacen_ven_lo_mismo) {
    const auto compartido = std::make_shared<cero::Almacen>(600s, std::nullopt);
    const auto una = montado().sesiones(compartido);
    const auto otra = cero::Servidor{router()}
                          .sesiones(compartido)
                          .accion("texto", [](const cero::Contexto& c) {
                              const auto& g = c.sesion();
                              if (!g) return cero::Respuesta::texto("nadie");
                              const auto tomado = g->tomar();
                              return cero::Respuesta::texto(
                                  std::string{g->sesion->leer("usuario").value_or("nadie")});
                          });

    const auto cookie = *cabecera(una.responder(peticion("GET", "/entrar")), "Set-Cookie");
    auto segunda = peticion("GET", "/a");
    segunda.cabeceras.emplace("cookie", cookie.substr(0, cookie.find(';')));
    COMPRUEBA(otra.responder(segunda).cuerpo == "ana", "SES-012");
}

}  // namespace

PRUEBAS_MAIN
