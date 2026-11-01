#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <variant>
#include <vector>

#include "cero/http2/conexion.hpp"
#include "cero/http2/flujo.hpp"
#include "cero/http2/hpack.hpp"
#include "cero/http2/trama.hpp"
#include "cero/servidor.hpp"
#include "cliente_h2.hpp"
#include "prueba.hpp"

namespace {

using namespace cero::http2;
using namespace std::chrono_literals;
using prueba::Cliente;
using prueba::conductor;
using prueba::estado;
using prueba::get;

Trama t(std::uint8_t tipo, std::uint8_t banderas, std::uint32_t flujo, std::string carga) {
    return {tipo, banderas, flujo, std::move(carga)};
}

std::string crudo(const std::vector<Cabecera>& cabeceras) {
    std::string salida;
    for (const auto& [nombre, valor] : cabeceras) {
        salida += '\x00';
        salida += static_cast<char>(nombre.size());
        salida += nombre;
        salida += static_cast<char>(valor.size());
        salida += valor;
    }
    return salida;
}

Sesion sesion() { return Sesion{Ajustes{}}; }

Digerido pedir(Sesion& s, std::uint32_t flujo, const std::vector<Cabecera>& cabeceras, bool fin) {
    const std::uint8_t banderas = kFinCabeceras | (fin ? kFinFlujo : 0);
    auto r = s.recibir(t(kHeaders, banderas, flujo, codificar(cabeceras)));
    COMPRUEBA(r.has_value(), "pedir no tenía que romper la conexión");
    return r ? std::move(*r) : Digerido{};
}

template <class T>
const T* primera(const Digerido& d) {
    for (const auto& a : d.acciones) {
        if (const auto* x = std::get_if<T>(&a)) return x;
    }
    return nullptr;
}

bool escribe(const Digerido& d, const Trama& esperada) {
    return std::ranges::any_of(d.acciones, [&](const Accion& a) {
        const auto* e = std::get_if<Escribir>(&a);
        return e != nullptr && e->trama == esperada;
    });
}

PRUEBA(h2_001_el_socket_ojea_el_preambulo_sin_consumirlo) {
    const auto ojear = [](std::string_view llega) {
        int fd[2];
        ::socketpair(AF_UNIX, SOCK_STREAM, 0, fd);
        (void)::write(fd[1], llega.data(), llega.size());
        ::shutdown(fd[1], SHUT_WR);
        DesdeSocket entrada{fd[0]};
        const bool es = entrada.asomar(kPreambulo);
        const int primero = entrada.octeto();
        ::close(fd[0]);
        ::close(fd[1]);
        return std::pair{es, primero};
    };
    COMPRUEBA(ojear("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\nresto") == std::pair{true, int{'P'}}, "H2-001");
    COMPRUEBA(ojear("GET / HTTP/1.1\r\n\r\n") == std::pair{false, int{'G'}}, "H2-001");
    COMPRUEBA(ojear("PRI *") == std::pair{false, int{'P'}}, "H2-001: cortado tampoco");
}

PRUEBA(h2_028_el_ping_vuelve_igual_con_ack) {
    auto s = sesion();
    const auto r = s.recibir(t(kPing, 0, 0, "12345678"));
    COMPRUEBA(r && escribe(*r, Trama::ping_ack("12345678")), "H2-028");
    const auto ack = s.recibir(t(kPing, kReconoce, 0, "12345678"));
    COMPRUEBA(ack && ack->acciones.empty(), "H2-028: un ACK no se contesta");
}

PRUEBA(h2_007_los_identificadores_no_retroceden) {
    auto s = sesion();
    pedir(s, 5, get(), true);
    const auto r = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 3, codificar(get())));
    COMPRUEBA(!r && r.error().codigo == Error::Protocolo, "H2-007");
}

PRUEBA(h2_029_y_030_los_flujos_son_independientes) {
    auto s = sesion();
    pedir(s, 1, get(), false);
    pedir(s, 3, get(), false);
    COMPRUEBA(s.activos() == 2, "H2-029: los dos a la vez, no en fila");
    const auto r = s.recibir(Trama::rst(1, Error::Anulado));
    COMPRUEBA(r && primera<Anulado>(*r) != nullptr, "H2-030: el anulado se avisa");
    COMPRUEBA(s.activos() == 1, "H2-030");
    const auto sigue = s.recibir(t(kData, kFinFlujo, 3, "x"));
    COMPRUEBA(sigue && !sigue->cortado, "H2-030: el flujo 3 no tiene la culpa");
}

PRUEBA(h2_031_la_ventana_del_flujo_nuevo_es_la_negociada) {
    Ajustes a;
    a.ventana_inicial = 1'000;
    Sesion s{a};
    pedir(s, 1, get(), false);
    COMPRUEBA(s.ventana_de(1) == 1'000, "H2-031");
}

PRUEBA(responder_no_cierra_el_flujo_del_cliente) {
    auto s = sesion();
    pedir(s, 1, get(), false);
    s.respondido(1);
    COMPRUEBA(s.activos() == 1, "sigue vivo hasta que el cliente diga END_STREAM");
    const auto r = s.recibir(t(kData, kFinFlujo, 1, "x"));
    COMPRUEBA(r && !r->cortado, "y lo que llega se sigue validando");
}

PRUEBA(h2_046_el_que_espera_respuesta_cuenta_para_el_tope) {
    Ajustes a;
    a.max_flujos = 1;
    Sesion s{a};
    pedir(s, 1, get(), true);
    COMPRUEBA(s.activos() == 1, "H2-046");
    const auto r = pedir(s, 3, get(), true);
    COMPRUEBA(r.cortado && r.cortado->codigo == Error::Rechazado, "H2-046: el tope toca");
    s.respondido(1);
    const auto despues = pedir(s, 5, get(), true);
    COMPRUEBA(primera<Peticion>(despues) != nullptr, "H2-046: respondido, deja sitio");
}

PRUEBA(h2_046_el_rechazado_no_descoloca_hpack) {
    Ajustes a;
    a.max_flujos = 1;
    Sesion s{a};
    pedir(s, 1, get(), true);
    std::string indexada = codificar(get());
    indexada += '\x40';
    indexada += static_cast<char>(8);
    indexada += "x-propia";
    indexada += static_cast<char>(1);
    indexada += "1";
    auto r = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 3, indexada));
    COMPRUEBA(r && r->cortado && r->cortado->codigo == Error::Rechazado, "H2-046: rechazado");
    s.respondido(1);
    std::string referencia = codificar(get());
    referencia += static_cast<char>(0x80 | 62);
    r = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 5, referencia));
    const auto* p = r ? primera<Peticion>(*r) : nullptr;
    COMPRUEBA(p != nullptr && p->cabeceras.back().first == "x-propia",
              "H2-046: el rechazado se decodificó y la tabla siguió alineada");
}

PRUEBA(h2_008_continuation_suelta) {
    auto s = sesion();
    const auto r = s.recibir(t(kContinuation, kFinCabeceras, 1, codificar(get())));
    COMPRUEBA(!r && r.error().codigo == Error::Protocolo, "H2-008");
}

PRUEBA(h2_032_un_bloque_repartido_en_continuation) {
    auto s = sesion();
    const auto entero = codificar(get());
    const auto mitad = entero.size() / 2;
    const auto uno = s.recibir(t(kHeaders, kFinFlujo, 1, entero.substr(0, mitad)));
    COMPRUEBA(uno && uno->acciones.empty(), "H2-032: a medias no hay petición");
    const auto dos = s.recibir(t(kContinuation, kFinCabeceras, 1, entero.substr(mitad)));
    const auto* p = dos ? primera<Peticion>(*dos) : nullptr;
    COMPRUEBA(p != nullptr && p->cabeceras == get(), "H2-032");
}

PRUEBA(h2_032_la_respuesta_que_no_cabe_sigue_en_continuation) {
    const std::string bloque(40'000, 'x');
    const auto tramas = tramas_de_cabeceras(1, bloque, 16'384, true);
    COMPRUEBA(tramas.size() == 3, "H2-032: tres tramas");
    COMPRUEBA(tramas[0].tipo == kHeaders && tramas[0].banderas == kFinFlujo, "H2-032: HEADERS");
    COMPRUEBA(tramas[1].tipo == kContinuation && tramas[1].banderas == 0, "H2-032");
    COMPRUEBA(tramas[2].tipo == kContinuation && tramas[2].banderas == kFinCabeceras, "H2-032");
    std::string junto;
    for (const auto& x : tramas) junto += x.carga;
    COMPRUEBA(junto == bloque, "H2-032: el bloque llega entero");
    const auto una = tramas_de_cabeceras(3, "abc", 16'384, false);
    COMPRUEBA(una.size() == 1 && una[0].banderas == kFinCabeceras, "cabe en una");
}

PRUEBA(nada_se_mete_por_medio_de_un_bloque_de_cabeceras) {
    auto s = sesion();
    (void)s.recibir(t(kHeaders, 0, 1, codificar(get())));
    const auto r = s.recibir(t(kPing, 0, 0, "12345678"));
    COMPRUEBA(!r && r.error().codigo == Error::Protocolo, "§6.2");
}

PRUEBA(h2_017_segundo_bloque_sin_fin_de_flujo) {
    auto s = sesion();
    pedir(s, 1, get(), false);
    const auto r = s.recibir(t(kHeaders, kFinCabeceras, 1, codificar({{"x-trailer", "1"}})));
    COMPRUEBA(!r && r.error().codigo == Error::Protocolo, "H2-017");
}

PRUEBA(h2_035_los_trailers_se_decodifican_aunque_se_tiren) {
    auto s = sesion();
    pedir(s, 1, get(), false);
    std::string trailer;
    trailer += '\x40';
    trailer += static_cast<char>(9);
    trailer += "x-trailer";
    trailer += static_cast<char>(1);
    trailer += "1";
    const auto r = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 1, trailer));
    const auto* c = r ? primera<Cuerpo>(*r) : nullptr;
    COMPRUEBA(c != nullptr && c->datos.empty() && c->fin, "H2-035");

    std::string siguiente = codificar(get());
    siguiente += static_cast<char>(0x80 | 62);
    const auto mas = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 3, siguiente));
    const auto* p = mas ? primera<Peticion>(*mas) : nullptr;
    COMPRUEBA(p != nullptr && p->cabeceras.back().first == "x-trailer",
              "H2-035: la tabla siguió alineada");
}

void malformada(std::string_view requisito, const std::vector<Cabecera>& cabeceras) {
    auto s = sesion();
    const auto r = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 1, crudo(cabeceras)));
    COMPRUEBA(r && escribe(*r, Trama::rst(1, Error::Protocolo)) && primera<Peticion>(*r) == nullptr,
              requisito);
    const auto sigue = pedir(s, 3, get(), true);
    COMPRUEBA(primera<Peticion>(sigue) != nullptr, requisito);
}

PRUEBA(h2_019_a_h2_026_las_peticiones_malformadas) {
    malformada("H2-019", {{":method", "GET"}, {":scheme", "https"}, {":path", "/"}, {"Host", "a"}});
    malformada("H2-020: falta :method", {{":scheme", "https"}, {":path", "/"}});
    malformada("H2-020: falta :scheme", {{":method", "GET"}, {":path", "/"}});
    malformada("H2-020: falta :path", {{":method", "GET"}, {":scheme", "https"}});
    malformada("H2-021", {{":method", "GET"}, {":scheme", "https"}, {":path", ""}});
    malformada("H2-022",
               {{":method", "GET"}, {":method", "POST"}, {":scheme", "https"}, {":path", "/"}});
    malformada("H2-023", {{":method", "GET"}, {"x-una", "1"}, {":scheme", "https"}, {":path", "/"}});
    malformada("H2-024", {{":method", "GET"}, {":scheme", "https"}, {":path", "/"}, {":vaya", "1"}});
    malformada("H2-025",
               {{":method", "GET"}, {":scheme", "https"}, {":path", "/"}, {"connection", "x"}});
    malformada("H2-026", {{":method", "GET"}, {":scheme", "https"}, {":path", "/"}, {"te", "gzip"}});
}

PRUEBA(h2_026_te_trailers_si_vale) {
    auto s = sesion();
    auto c = get();
    c.emplace_back("te", "trailers");
    COMPRUEBA(primera<Peticion>(pedir(s, 1, c, true)) != nullptr, "H2-026");
}

PRUEBA(h2_018_un_metodo_desconocido_corta_el_flujo_y_no_la_conexion) {
    auto s = sesion();
    const std::vector<Cabecera> c{
        {":method", "INVENTADO"}, {":scheme", "https"}, {":path", "/"}, {":authority", "a"}};
    COMPRUEBA(primera<Peticion>(pedir(s, 1, c, true)) != nullptr, "H2-018: llega como petición");
    COMPRUEBA(!cero::metodo_conocido("INVENTADO") && cero::metodo_conocido("GET"),
              "H2-018: y el conductor sabe que no lo implementa");
}

PRUEBA(el_credito_se_devuelve_al_leer_y_pasarse_corta) {
    auto s = sesion();
    pedir(s, 1, get(), false);
    const auto r = s.recibir(t(kData, 0, 1, std::string(100, 'x')));
    COMPRUEBA(r && escribe(*r, Trama::ventana(0, 100)), "ventana de conexión");
    COMPRUEBA(r && escribe(*r, Trama::ventana(1, 100)), "ventana del flujo");
    COMPRUEBA(s.ventana_de(1) == 65'535, "el flujo vuelve a estar entero");

    Ajustes a;
    a.ventana_inicial = 10;
    Sesion apretada{a};
    pedir(apretada, 1, get(), false);
    const auto fallo = apretada.recibir(t(kData, 0, 1, std::string(11, 'x')));
    COMPRUEBA(!fallo && fallo.error().codigo == Error::ControlDeFlujo, "§6.9.1");
}

PRUEBA(el_cuerpo_sobre_un_flujo_cerrado_devuelve_el_credito_de_la_conexion) {
    auto s = sesion();
    pedir(s, 1, get(), true);
    s.respondido(1);
    for (int i = 0; i < 10; ++i) {
        const auto r = s.recibir(t(kData, 0, 1, std::string(10'000, 'x')));
        COMPRUEBA(r && r->cortado && r->cortado->codigo == Error::FlujoCerrado,
                  "un flujo cerrado corta el flujo");
        COMPRUEBA(r && escribe(*r, Trama::ventana(0, 10'000)), "y la conexión recupera lo suyo");
    }
}

PRUEBA(h2_047_cuerpo_despues_del_fin_de_flujo) {
    auto s = sesion();
    pedir(s, 1, get(), true);
    const auto r = s.recibir(t(kData, 0, 1, "tarde"));
    COMPRUEBA(r && r->cortado && r->cortado->codigo == Error::FlujoCerrado, "H2-047");
    COMPRUEBA(r && escribe(*r, Trama::rst(1, Error::FlujoCerrado)), "H2-047");
}

PRUEBA(h2_047_no_hay_cabeceras_despues_del_fin) {
    auto s = sesion();
    pedir(s, 1, get(), true);
    std::string indexada;
    indexada += '\x40';
    indexada += static_cast<char>(8);
    indexada += "x-propia";
    indexada += static_cast<char>(1);
    indexada += "1";
    const auto r = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 1, indexada));
    COMPRUEBA(r && r->cortado && r->cortado->codigo == Error::FlujoCerrado, "H2-047");
    COMPRUEBA(r && escribe(*r, Trama::rst(1, Error::FlujoCerrado)), "H2-047");

    std::string referencia = codificar(get());
    referencia += static_cast<char>(0x80 | 62);
    const auto mas = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 3, referencia));
    const auto* p = mas ? primera<Peticion>(*mas) : nullptr;
    COMPRUEBA(p != nullptr && p->cabeceras.back().first == "x-propia",
              "H2-047: el bloque cortado se decodificó igual");
}

PRUEBA(el_relleno_ni_llega_ni_se_perdona) {
    auto s = sesion();
    pedir(s, 1, get(), false);
    std::string carga{"\x03hola"};
    carga += std::string(3, '\0');
    const auto r = s.recibir(t(kData, kRelleno, 1, carga));
    const auto* c = r ? primera<Cuerpo>(*r) : nullptr;
    COMPRUEBA(c != nullptr && c->datos == "hola", "§6.1: el relleno no llega");
    const auto fallo = s.recibir(t(kData, kRelleno, 1, std::string{"\xc8x"}));
    COMPRUEBA(!fallo && fallo.error().codigo == Error::Protocolo, "§6.1: ni se perdona");
}

PRUEBA(h2_036_un_bloque_de_cabeceras_sin_fin) {
    auto s = sesion();
    s.topes.bloque_cabeceras = 4'096;
    (void)s.recibir(t(kHeaders, 0, 1, codificar(get())));
    std::optional<Error> fallo;
    for (int i = 0; i < 100 && !fallo; ++i) {
        const auto r = s.recibir(t(kContinuation, 0, 1, std::string(512, '\0')));
        if (!r) fallo = r.error().codigo;
    }
    COMPRUEBA(fallo == Error::Calma, "H2-036");
}

PRUEBA(h2_037_abrir_y_anular_en_bucle) {
    auto s = sesion();
    s.topes.anulados = 10;
    std::optional<Error> fallo;
    for (std::uint32_t id = 1; id < 100 && !fallo; id += 2) {
        pedir(s, id, get(), false);
        const auto r = s.recibir(Trama::rst(id, Error::Anulado));
        if (!r) fallo = r.error().codigo;
    }
    COMPRUEBA(fallo == Error::Calma, "H2-037");
}

PRUEBA(h2_038_tramas_de_control_sin_abrir_nada) {
    auto s = sesion();
    s.topes.control_sin_flujo = 20;
    std::optional<Error> fallo;
    for (int i = 0; i < 100 && !fallo; ++i) {
        const auto r = s.recibir(t(kPing, 0, 0, "12345678"));
        if (!r) fallo = r.error().codigo;
    }
    COMPRUEBA(fallo == Error::Calma, "H2-038");

    auto trabajando = sesion();
    trabajando.topes.control_sin_flujo = 20;
    bool siempre = true;
    for (std::uint32_t id = 1; id < 200; id += 2) {
        siempre = siempre && trabajando.recibir(t(kPing, 0, 0, "12345678")).has_value();
        pedir(trabajando, id, get(), true);
        trabajando.respondido(id);
    }
    COMPRUEBA(siempre, "H2-038: un cliente que trabaja no choca");
}

PRUEBA(h2_034_las_cabeceras_de_la_respuesta) {
    const auto bloque = cabeceras_de_respuesta(
        200, {{"Content-Type", "text/plain"}, {"Connection", "keep-alive"}, {"X-Propia", "1"},
              {"Transfer-Encoding", "chunked"}, {"Keep-Alive", "5"}});
    Decodificador d{4096, 16'384};
    const auto c = d.decodificar(bloque);
    std::vector<std::string> nombres;
    if (c) {
        for (const auto& [n, v] : *c) nombres.push_back(n);
    }
    COMPRUEBA(nombres == std::vector<std::string>{":status", "content-type", "x-propia"},
              "H2-033 y H2-034");
}

PRUEBA(h2_042_los_ajustes_del_cliente_no_mueven_los_nuestros) {
    auto s = sesion();
    const Ajustes nuestros{};
    std::string carga;
    for (const auto& [id, v] : std::vector<std::pair<std::uint16_t, std::uint32_t>>{
             {0x3, 0xffff'ffff}, {0x4, 1'000}, {0x5, 1'000'000}, {0x6, 0xffff'ffff}}) {
        carga += static_cast<char>(id >> 8);
        carga += static_cast<char>(id & 0xff);
        for (int d : {24, 16, 8, 0}) carga += static_cast<char>((v >> d) & 0xff);
    }
    const auto r = s.recibir(t(kSettings, 0, 0, carga));
    COMPRUEBA(r && escribe(*r, Trama::settings_ack()), "H2-042: se reconocen");
    COMPRUEBA(s.mios().max_flujos == nuestros.max_flujos, "H2-042: el tope de flujos es nuestro");
    COMPRUEBA(s.mios().max_cabeceras == nuestros.max_cabeceras, "H2-042: y el de cabeceras");
    COMPRUEBA(s.mios().max_trama == nuestros.max_trama, "H2-042: y lo que admitimos leer");
    COMPRUEBA(s.par().ventana_inicial == 1'000 && s.par().max_trama == 1'000'000,
              "H2-042: lo suyo vale para lo que él recibe");
    pedir(s, 1, get(), false);
    COMPRUEBA(s.ventana_de(1) == nuestros.ventana_inicial, "H2-042: la entrada sigue igual");
}

PRUEBA(h2_042_al_cliente_se_le_suponen_los_del_rfc) {
    const auto s = sesion();
    COMPRUEBA(s.par().ventana_inicial == 65'535 && s.par().max_trama == 16'384, "H2-042");
}

PRUEBA(h2_043_el_cuerpo_tiene_que_medir_lo_que_dijo) {
    auto post = get();
    post[0].second = "POST";
    post.emplace_back("content-length", "5");

    auto corto = sesion();
    pedir(corto, 1, post, false);
    auto r = corto.recibir(t(kData, kFinFlujo, 1, "abc"));
    COMPRUEBA(r && r->cortado && r->cortado->codigo == Error::Protocolo, "H2-043: corto");

    auto largo = sesion();
    pedir(largo, 1, post, false);
    r = largo.recibir(t(kData, 0, 1, "abcdef"));
    COMPRUEBA(r && r->cortado, "H2-043: largo, se sabe al momento");

    auto sin_cuerpo = sesion();
    const auto d = pedir(sin_cuerpo, 1, post, true);
    COMPRUEBA(d.cortado.has_value(), "H2-043: END_STREAM en las cabeceras mide cero");

    auto no_numero = get();
    no_numero.emplace_back("content-length", "cinco");
    auto raro = sesion();
    COMPRUEBA(pedir(raro, 1, no_numero, false).cortado.has_value(), "H2-043: no es un número");

    auto relleno = sesion();
    pedir(relleno, 1, post, false);
    std::string carga{"\x04"};
    carga += "abcde";
    carga += std::string(4, '\0');
    r = relleno.recibir(t(kData, kRelleno | kFinFlujo, 1, carga));
    COMPRUEBA(r && !r->cortado, "H2-043: el relleno no cuenta como cuerpo");

    auto con_trailers = sesion();
    pedir(con_trailers, 1, post, false);
    (void)con_trailers.recibir(t(kData, 0, 1, "abc"));
    r = con_trailers.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, 1, codificar({{"x-t", "1"}})));
    COMPRUEBA(r && r->cortado, "H2-043: los trailers no tapan un cuerpo corto");
}

PRUEBA(h2_043_el_cuerpo_que_cuadra_pasa) {
    auto post = get();
    post[0].second = "POST";
    post.emplace_back("content-length", "5");
    auto s = sesion();
    pedir(s, 1, post, false);
    (void)s.recibir(t(kData, 0, 1, "ab"));
    const auto r = s.recibir(t(kData, kFinFlujo, 1, "cde"));
    const auto* c = r ? primera<Cuerpo>(*r) : nullptr;
    COMPRUEBA(c != nullptr && c->fin && !r->cortado, "H2-043");
}

PRUEBA(h2_044_y_049_la_prioridad_se_descarta_pero_se_mira) {
    auto s = sesion();
    std::string a_si_mismo{"\x00\x00\x00\x01\x10", 5};
    auto r = s.recibir(t(kPriority, 0, 1, a_si_mismo));
    COMPRUEBA(r && r->cortado && r->cortado->codigo == Error::Protocolo, "H2-044: PRIORITY");

    auto h = sesion();
    std::string carga{"\x00\x00\x00\x03\x10", 5};
    carga += codificar(get());
    r = h.recibir(t(kHeaders, kFinCabeceras | kFinFlujo | kPrioridad, 3, carga));
    COMPRUEBA(r && r->cortado && primera<Peticion>(*r) == nullptr, "H2-044: HEADERS");

    auto otra = sesion();
    std::string ajena{"\x00\x00\x00\x01\x10", 5};
    r = otra.recibir(t(kPriority, 0, 3, ajena));
    COMPRUEBA(r && !r->cortado && r->acciones.empty(), "H2-044: la que vale se tira");

    const auto cero_ = otra.recibir(t(kPriority, 0, 0, ajena));
    COMPRUEBA(!cero_ && cero_.error().codigo == Error::Protocolo, "H2-049");
}

PRUEBA(h2_045_no_hay_credito_para_un_flujo_ocioso) {
    auto s = sesion();
    const auto r = s.recibir(Trama::ventana(1, 100));
    COMPRUEBA(!r && r.error().codigo == Error::Protocolo, "H2-045");
}

PRUEBA(los_flujos_ociosos_no_admiten_nada) {
    auto s = sesion();
    auto r = s.recibir(t(kData, 0, 1, "x"));
    COMPRUEBA(!r && r.error().codigo == Error::Protocolo, "§5.1: DATA ocioso");
    auto otra = sesion();
    r = otra.recibir(Trama::rst(1, Error::Anulado));
    COMPRUEBA(!r && r.error().codigo == Error::Protocolo, "§5.1: RST_STREAM ocioso");
}

PRUEBA(el_ascenso_abre_el_flujo_uno_ya_medio_cerrado) {
    auto s = sesion();
    const auto r = s.ascender(std::string{"\x00\x04\x00\x00\x03\xe8", 6});
    COMPRUEBA(r.has_value(), "h2c: los ajustes del ascenso valen");
    COMPRUEBA(s.par().ventana_inicial == 1'000, "h2c: son del cliente");
    COMPRUEBA(s.ultimo() == 1 && s.activos() == 1, "h2c: el flujo 1 existe");
    const auto tarde = s.recibir(t(kData, 0, 1, "x"));
    COMPRUEBA(tarde && tarde->cortado && tarde->cortado->codigo == Error::FlujoCerrado,
              "h2c: y el cliente ya terminó de hablar en él");
    COMPRUEBA(!sesion().ascender("xxxxx").has_value(), "h2c: lo que no es SETTINGS no vale");
}

PRUEBA(h2c_lee_el_ascenso) {
    cero::Peticion p;
    p.metodo = "GET";
    p.destino = "/";
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("connection", "Upgrade, HTTP2-Settings");
    p.cabeceras.emplace("upgrade", "h2c");
    p.cabeceras.emplace("http2-settings", "AAMAAABk");
    COMPRUEBA(pide_h2c(p), "h2c");
    COMPRUEBA(base64url("AAMAAABk") == std::string{"\x00\x03\x00\x00\x00\x64", 6}, "h2c: base64url");
    COMPRUEBA(!base64url("A*").has_value(), "h2c: base64url inválido");
    p.cabeceras.erase("http2-settings");
    COMPRUEBA(!pide_h2c(p), "h2c: sin HTTP2-Settings no hay ascenso");
}

PRUEBA(h2_027_el_conductor_manda_sus_settings_primero) {
    auto [d, hilo] = conductor([](const cero::Peticion&) { return cero::Respuesta::texto("hola"); });
    {
        Cliente c{d};
        c.abrir_h2();
        const auto primera_ = c.trama();
        COMPRUEBA(primera_ && primera_->tipo == kSettings && primera_->banderas == 0, "H2-027");
    }
    hilo.join();
}

PRUEBA(h2_029_el_conductor_atiende_los_flujos_en_paralelo) {
    auto [d, hilo] = conductor([](const cero::Peticion& p) {
        std::this_thread::sleep_for(300ms);
        return cero::Respuesta::texto(p.destino);
    });
    {
        Cliente c{d};
        c.abrir_h2();
        const auto empezo = std::chrono::steady_clock::now();
        c.mandar(Trama{kHeaders, kFinCabeceras | kFinFlujo, 1, codificar(get())});
        c.mandar(Trama{kHeaders, kFinCabeceras | kFinFlujo, 3, codificar(get())});
        int fines = 0;
        for (auto x = c.trama(); x && fines < 2; x = fines < 2 ? c.trama() : std::nullopt) {
            if ((x->tipo == kData || x->tipo == kHeaders) && x->fin_flujo()) ++fines;
        }
        const auto tardo = std::chrono::steady_clock::now() - empezo;
        COMPRUEBA(fines == 2, "H2-029: las dos respuestas");
        COMPRUEBA(tardo < 550ms, "H2-029: en paralelo, no en fila");
    }
    hilo.join();
}

PRUEBA(h2_030_el_conductor_sigue_tras_un_flujo_cortado) {
    auto [d, hilo] = conductor([](const cero::Peticion&) { return cero::Respuesta::texto("hola"); });
    {
        Cliente c{d};
        c.abrir_h2();
        c.mandar(Trama{kHeaders, kFinCabeceras | kFinFlujo, 1, crudo({{":method", "GET"}})});
        const auto rst = c.hasta(kRstStream);
        COMPRUEBA(rst && rst->flujo == 1, "H2-030: el malo se corta");
        c.mandar(Trama{kHeaders, kFinCabeceras | kFinFlujo, 3, codificar(get())});
        const auto r = c.hasta(kHeaders);
        COMPRUEBA(r && r->flujo == 3 && estado(*r) == "200", "H2-030: y el siguiente responde");
    }
    hilo.join();
}

PRUEBA(h2_018_el_conductor_contesta_501_en_el_flujo) {
    auto [d, hilo] = conductor([](const cero::Peticion&) { return cero::Respuesta::texto("hola"); });
    {
        Cliente c{d};
        c.abrir_h2();
        auto raro = get();
        raro[0].second = "INVENTADO";
        c.mandar(Trama{kHeaders, kFinCabeceras | kFinFlujo, 1, codificar(raro)});
        const auto r = c.hasta(kHeaders);
        COMPRUEBA(r && r->flujo == 1 && estado(*r) == "501", "H2-018");
        c.mandar(Trama{kHeaders, kFinCabeceras | kFinFlujo, 3, codificar(get())});
        const auto s = c.hasta(kHeaders);
        COMPRUEBA(s && s->flujo == 3 && estado(*s) == "200", "H2-018: la conexión sigue");
    }
    hilo.join();
}

PRUEBA(el_conductor_entrega_el_cuerpo_y_la_autoridad) {
    std::string visto;
    auto [d, hilo] = conductor([&visto](const cero::Peticion& p) {
        visto = p.metodo + " " + p.destino + " " + std::string{p.cabecera("host").value_or("")} +
                " " + p.cuerpo;
        return cero::Respuesta::texto(p.cuerpo);
    });
    {
        Cliente c{d};
        c.abrir_h2();
        auto post = get();
        post[0].second = "POST";
        c.mandar(Trama{kHeaders, kFinCabeceras, 1, codificar(post)});
        c.mandar(Trama{kData, 0, 1, "ho"});
        c.mandar(Trama{kData, kFinFlujo, 1, "la"});
        const auto datos = c.hasta(kData);
        COMPRUEBA(datos && datos->carga == "hola" && datos->fin_flujo(), "el eco");
    }
    hilo.join();
    COMPRUEBA(visto == "POST / a hola", ":authority pasa a host");
}

PRUEBA(h2_046_y_037_repetir_headers_y_data_no_salta_el_tope) {
    Ajustes a;
    a.max_flujos = 2;
    for (const bool con_data : {true, false}) {
        Sesion s{a};
        int lanzadas = 0;
        bool rechazo = false;
        bool calma = false;
        for (std::uint32_t id = 1; id < 2'000 && !calma; id += 2) {
            auto r = s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, id, codificar(get())));
            if (!r) {
                calma = r.error().codigo == Error::Calma;
                break;
            }
            if (primera<Peticion>(*r) != nullptr) ++lanzadas;
            if (r->cortado && r->cortado->codigo == Error::Rechazado) rechazo = true;
            r = con_data ? s.recibir(t(kData, 0, id, ""))
                         : s.recibir(t(kHeaders, kFinCabeceras | kFinFlujo, id, codificar(get())));
            if (!r) calma = r.error().codigo == Error::Calma;
        }
        COMPRUEBA(lanzadas <= 2, "H2-046: el que espera respuesta sigue contando aunque se corte");
        COMPRUEBA(rechazo || calma, "H2-046 y H2-037: o se rechaza o se corta la conexión");
    }
}

PRUEBA(h2_046_el_conductor_no_lanza_mas_hilos_que_el_tope) {
    std::atomic<int> a_la_vez{0};
    std::atomic<int> maximo{0};
    std::atomic<int> llamadas{0};
    Ajustes a;
    a.max_flujos = 2;
    auto [d, hilo] = conductor(
        [&](const cero::Peticion&) {
            ++llamadas;
            const int ahora = ++a_la_vez;
            for (int m = maximo; ahora > m && !maximo.compare_exchange_weak(m, ahora);) {
            }
            std::this_thread::sleep_for(200ms);
            --a_la_vez;
            return cero::Respuesta::texto("lento");
        },
        a);
    {
        Cliente c{d};
        c.abrir_h2();
        std::string rafaga;
        for (std::uint32_t id = 1; id < 400; id += 2) {
            rafaga += Trama{kHeaders, kFinCabeceras | kFinFlujo, id, codificar(get())}.octetos();
            rafaga += Trama{kData, 0, id, ""}.octetos();
        }
        c.mandar(rafaga);
        c.mandar(Trama{kPing, 0, 0, "12345678"});
        std::optional<Trama> x;
        bool rechazado = false;
        bool adios = false;
        while ((x = c.trama())) {
            if (x->tipo == kRstStream &&
                entero32(x->carga, 0) == static_cast<std::uint32_t>(Error::Rechazado)) {
                rechazado = true;
            }
            if (x->tipo == kGoaway) adios = true;
            if ((x->tipo == kPing && (x->banderas & kReconoce) != 0) || adios) break;
        }
        COMPRUEBA(rechazado || adios, "H2-046: el tope se nota en el cable");
    }
    hilo.join();
    COMPRUEBA(maximo <= 2, "H2-046: nunca más hilos a la vez que el tope");
    COMPRUEBA(llamadas <= 4, "H2-037: no se puede pedir trabajo sin límite");
}

PRUEBA(h2_046_un_window_update_cero_libera_el_hueco) {
    Ajustes a;
    a.max_flujos = 1;
    Sesion s{a};
    pedir(s, 1, get(), false);
    const auto r = s.recibir(Trama{kWindowUpdate, 0, 1, std::string(4, '\0')});
    COMPRUEBA(r && r->cortado, "§6.9: incremento cero corta el flujo");
    COMPRUEBA(s.activos() == 0, "H2-046: y el hueco queda libre");
    COMPRUEBA(primera<Peticion>(pedir(s, 3, get(), true)) != nullptr, "H2-046: cabe otro");
    const auto tarde = s.recibir(t(kData, 0, 1, "x"));
    COMPRUEBA(tarde && tarde->cortado && tarde->cortado->codigo == Error::FlujoCerrado,
              "§5.1: lo que llega después es de un flujo cerrado");
}

PRUEBA(h2_044_y_046_la_prioridad_sobre_si_mismo_libera_el_hueco) {
    Ajustes a;
    a.max_flujos = 1;
    Sesion s{a};
    pedir(s, 1, get(), false);
    const auto r = s.recibir(t(kPriority, 0, 1, std::string{"\x00\x00\x00\x01\x10", 5}));
    COMPRUEBA(r && r->cortado, "H2-044");
    COMPRUEBA(s.activos() == 0, "H2-046: y el hueco queda libre");
}

PRUEBA(h2_038_los_rst_que_mandamos_tienen_tope) {
    auto s = sesion();
    s.topes.cortes = 50;
    pedir(s, 1, get(), true);
    s.respondido(1);
    std::optional<Error> fallo;
    for (int i = 0; i < 1'000 && !fallo; ++i) {
        const auto r = s.recibir(t(kData, 0, 1, ""));
        if (!r) fallo = r.error().codigo;
    }
    COMPRUEBA(fallo == Error::Calma, "H2-038: un RST por trama no sale gratis sin fin");
}

PRUEBA(h2c_la_peticion_ascendida_llega_como_http2) {
    std::string version;
    std::string resto;
    int fd[2];
    ::socketpair(AF_UNIX, SOCK_STREAM, 0, fd);
    cero::Peticion p;
    p.metodo = "GET";
    p.destino = "/";
    p.version = "HTTP/1.1";
    p.cabeceras.emplace("connection", "Upgrade, HTTP2-Settings");
    p.cabeceras.emplace("upgrade", "h2c");
    p.cabeceras.emplace("http2-settings", "AAMAAABk");
    std::thread hilo{[&, d = fd[0]] {
        DesdeSocket entrada{d};
        servir(entrada, Ajustes{}, [&](const cero::Peticion& q) {
            version = q.version;
            resto = std::string{q.cabecera("upgrade").value_or("")} +
                    std::string{q.cabecera("http2-settings").value_or("")};
            return cero::Respuesta::texto("hola");
        }, p);
        ::close(d);
    }};
    {
        Cliente c{fd[1]};
        while (c.linea().size() > 1) {
        }
        c.abrir_h2();
        const auto r = c.hasta(kHeaders);
        COMPRUEBA(r && r->flujo == 1, "h2c: el flujo 1 responde");
    }
    hilo.join();
    COMPRUEBA(version == "HTTP/2" && resto.empty(), "h2c: sin rastro del ascenso");
}

unsigned short puerto() {
    static const unsigned short p = [] {
        const auto elegido = static_cast<unsigned short>(20'000 + ::getpid() % 20'000);
        cero::Router r;
        (void)r.ruta("GET", "/", "raiz");
        std::thread{[elegido, r = std::move(r)]() mutable {
            cero::Servidor{std::move(r)}
                .accion("raiz", [](const cero::Contexto&) { return cero::Respuesta::texto("cero"); })
                .direccion("127.0.0.1")
                .escuchar(elegido);
        }}.detach();
        return elegido;
    }();
    return p;
}

int conectar() {
    for (int intento = 0; intento < 50; ++intento) {
        const int d = ::socket(AF_INET, SOCK_STREAM, 0);
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        a.sin_port = htons(puerto());
        if (::connect(d, reinterpret_cast<sockaddr*>(&a), sizeof a) == 0) return d;
        ::close(d);
        std::this_thread::sleep_for(50ms);
    }
    return -1;
}

std::string http11() {
    Cliente c{conectar()};
    c.mandar("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n");
    return c.linea();
}

PRUEBA(h2_001_http11_y_h2_conviven_en_el_mismo_puerto) {
    COMPRUEBA(http11().starts_with("HTTP/1.1 200"), "H2-001: primero HTTP/1.1");
    {
        Cliente c{conectar()};
        c.abrir_h2();
        c.mandar(Trama{kHeaders, kFinCabeceras | kFinFlujo, 1, codificar(get())});
        const auto r = c.hasta(kHeaders);
        COMPRUEBA(r && r->flujo == 1 && estado(*r) == "200", "H2-001: luego h2 por prefacio");
    }
    COMPRUEBA(http11().starts_with("HTTP/1.1 200"), "H2-001: y HTTP/1.1 otra vez");
}

PRUEBA(h2_001_un_preambulo_falso_es_http11) {
    Cliente c{conectar()};
    c.mandar("PRI * HTTP/2.0\r\n\r\nXX\r\n\r\n");
    COMPRUEBA(c.linea().starts_with("HTTP/1.1 501"), "H2-001: no se atiende como h2");
}

PRUEBA(h2c_por_upgrade_en_el_mismo_puerto) {
    Cliente c{conectar()};
    c.mandar(
        "GET / HTTP/1.1\r\nHost: a\r\nConnection: Upgrade, HTTP2-Settings\r\n"
        "Upgrade: h2c\r\nHTTP2-Settings: AAMAAABk\r\n\r\n");
    COMPRUEBA(c.linea().starts_with("HTTP/1.1 101"), "h2c: 101");
    while (c.linea().size() > 1) {
    }
    c.abrir_h2();
    const auto primera_ = c.trama();
    COMPRUEBA(primera_ && primera_->tipo == kSettings, "h2c: H2-027 también aquí");
    const auto r = c.hasta(kHeaders);
    COMPRUEBA(r && r->flujo == 1 && estado(*r) == "200", "h2c: la petición es el flujo 1");
}

PRUEBA(h2_048_el_goaway_se_puede_leer) {
    Cliente c{conectar()};
    c.abrir_h2();
    std::string junto = Trama{kHeaders, kFinCabeceras, 2, codificar(get())}.octetos();
    for (int i = 0; i < 2'000; ++i) junto += Trama::ping_ack("12345678").octetos();
    c.mandar(junto);
    const auto adios = c.hasta(kGoaway);
    COMPRUEBA(adios.has_value(), "H2-048: el GOAWAY llega");
    COMPRUEBA(adios && entero32(adios->carga, 4) == static_cast<std::uint32_t>(Error::Protocolo),
              "H2-048: con su motivo");
}

}

PRUEBAS_MAIN
