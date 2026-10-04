// Una prueba por requisito de `spec/sesiones.md`, citándolo.
//
// No están copiadas de la batería de Java ni de la de Rust: están escritas leyendo el contrato. Si
// el contrato fuera una descripción de Java con otras palabras, aquí se notaría.

#include <atomic>
#include <chrono>
#include <set>
#include <thread>
#include <vector>

#include "cero/sesion.hpp"
#include "prueba.hpp"

namespace {

using namespace std::chrono_literals;

cero::Almacen almacen() { return cero::Almacen{600s, std::nullopt}; }

PRUEBA(ses_001_sin_cookie_no_se_recupera_nada) {
    auto a = almacen();
    (void)a.crear();
    COMPRUEBA(!a.recuperar(std::nullopt).has_value(), "SES-001");
    COMPRUEBA(!a.recuperar("inventado").has_value(), "SES-001");
    COMPRUEBA(a.cuantas() == 1, "SES-001: y leer no crea nada");
}

PRUEBA(ses_002_el_identificador_es_largo_y_de_fuente_apta) {
    const auto id = cero::identificador();
    COMPRUEBA(id && id->size() >= 40, "SES-002");
    COMPRUEBA(id && id->find_first_not_of("0123456789abcdef") == std::string::npos,
              "SES-002: base16, sin alfabeto ambiguo");
}

PRUEBA(ses_003_dos_sesiones_no_comparten_identificador) {
    // Mil y no dos: con dos, un generador roto que devuelva uno de cada dos valores pasaría.
    auto a = almacen();
    std::set<std::string> vistos;
    for (int i = 0; i < 1000; ++i) vistos.insert(a.crear()->sesion->id());
    COMPRUEBA(vistos.size() == 1000, "SES-003");
}

PRUEBA(ses_004_invalidar_deja_la_sesion_inutilizable) {
    auto a = almacen();
    const auto g = *a.crear();
    const auto id = g.sesion->id();
    COMPRUEBA(g.sesion->poner("usuario", "ana"), "se puede escribir");
    g.sesion->invalidar();

    COMPRUEBA(!g.sesion->poner("usuario", "otra"), "SES-004: escribir falla");
    COMPRUEBA(!g.sesion->leer("usuario").has_value(), "SES-004: y leer también");
    COMPRUEBA(!a.recuperar(id).has_value(), "SES-004: y no se recrea en silencio");
}

PRUEBA(ses_005_y_006_rotar) {
    auto a = almacen();
    const auto g = *a.crear();
    (void)g.sesion->cookie_pendiente();  // la de la creación, ya emitida
    const auto viejo = g.sesion->id();
    (void)g.sesion->poner("usuario", "ana");

    const auto nuevo = a.rotar(g);
    COMPRUEBA(nuevo && *nuevo != viejo, "SES-005: el identificador cambia");
    COMPRUEBA(g.sesion->leer("usuario") == "ana", "SES-005: los atributos se conservan");
    COMPRUEBA(a.recuperar(*nuevo).has_value(), "SES-005: se encuentra por el nuevo");
    COMPRUEBA(!a.recuperar(viejo).has_value(), "SES-005: y el viejo ya no vale");

    COMPRUEBA(g.sesion->cookie_pendiente() == nuevo, "SES-005: hay que reemitir la cookie");
    COMPRUEBA(!g.sesion->cookie_pendiente().has_value(), "SES-005: exactamente una vez");

    g.sesion->invalidar();
    COMPRUEBA(!a.rotar(g).has_value(), "SES-006");
}

PRUEBA(ses_007_dos_peticiones_simultaneas_no_se_pisan) {
    // El candado va **dentro** de la sesión y no alrededor del almacén: dos peticiones sobre la
    // misma sesión no pueden perder escrituras, y dos sobre sesiones distintas no se esperan.
    auto a = almacen();
    const auto g = *a.crear();
    constexpr int kHilos = 8;
    constexpr int kVueltas = 500;

    std::vector<std::jthread> hilos;
    for (int h = 0; h < kHilos; ++h) {
        hilos.emplace_back([&g, h] {
            for (int i = 0; i < kVueltas; ++i) {
                const auto tomado = g.tomar();
                (void)g.sesion->poner(cero::texto("clave-{}-{}", h, i), "x");
            }
        });
    }
    hilos.clear();  // los `jthread` se esperan al destruirse
    COMPRUEBA(g.sesion->atributos().size() == kHilos * kVueltas, "SES-007");
}

PRUEBA(ses_008_y_011_la_cookie_se_emite_una_vez) {
    auto a = almacen();
    const auto g = *a.crear();
    COMPRUEBA(g.sesion->cookie_pendiente() == g.sesion->id(), "SES-008: la que la crea la lleva");
    COMPRUEBA(!g.sesion->cookie_pendiente().has_value(), "SES-008: las siguientes no");
    // SES-011: consultarlo **consume**. Que el método no sea `const` es lo que impide llamarlo
    // dos veces por descuido desde dos caminos de salida, uno por protocolo.
    COMPRUEBA(!g.sesion->cookie_pendiente().has_value(), "SES-011");
}

PRUEBA(ses_009_los_atributos_de_la_cookie) {
    const auto sin_tls = cero::cabecera_cookie("abc", false);
    COMPRUEBA(sin_tls.find("HttpOnly") != std::string::npos, "SES-009");
    COMPRUEBA(sin_tls.find("SameSite=Lax") != std::string::npos, "SES-009");
    COMPRUEBA(sin_tls.find("Secure") == std::string::npos, "SES-009: sin TLS no se miente");
    COMPRUEBA(cero::cabecera_cookie("abc", true).find("Secure") != std::string::npos,
              "SES-009: con TLS sí");
}

PRUEBA(el_identificador_se_saca_de_la_cabecera_cookie) {
    COMPRUEBA(cero::id_de_cookie("otra=1; cero_sid=abc; mas=2") == "abc", "entre varias");
    COMPRUEBA(cero::id_de_cookie("cero_sid=abc") == "abc", "sola");
    COMPRUEBA(!cero::id_de_cookie("otra=1").has_value(), "y si no está, no está");
    COMPRUEBA(!cero::id_de_cookie(std::nullopt).has_value(), "sin cabecera tampoco");
}

PRUEBA(una_sesion_caducada_no_vuelve_a_valer) {
    cero::Almacen apretado{0s, std::nullopt};
    const auto id = apretado.crear()->sesion->id();
    std::this_thread::sleep_for(5ms);
    COMPRUEBA(!apretado.recuperar(id).has_value(), "caducada por inactividad");
    COMPRUEBA(apretado.cuantas() == 0, "y se suelta al tocarla, sin barrido aparte");
}

PRUEBA(una_sesion_rescatada_no_nace_sucia_ni_pidiendo_cookie) {
    const auto ahora = cero::Reloj::now();
    auto s = cero::Sesion::rescatada("abc", {{"usuario", "ana"}}, ahora, ahora);
    COMPRUEBA(s->leer("usuario") == "ana", "vuelve con sus atributos");
    COMPRUEBA(!s->cookie_pendiente().has_value(), "el cliente ya tiene la cookie: es la que usó");
    COMPRUEBA(!s->sucia(), "y no hay nada que volver a guardar");
}

}  // namespace

PRUEBAS_MAIN
