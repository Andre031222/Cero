#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "cero/http2/conexion.hpp"
#include "cero/http2/flujo.hpp"
#include "cero/http2/hpack.hpp"
#include "cero/http2/trama.hpp"
#include "cero/http2/ventanas.hpp"
#include "cero/peticion.hpp"
#include "cero/respuesta.hpp"
#include "cliente_h2.hpp"
#include "prueba.hpp"

using namespace std;

namespace {

using namespace cero::http2;
using prueba::ajuste;
using prueba::Cliente;
using prueba::conductor;
using prueba::get;

constexpr uint16_t kVentanaInicial = 0x4;
constexpr uint32_t kTope = 0x7fff'ffff;
const auto kSiempre = chrono::steady_clock::now() + 24h;

Trama pide(uint32_t flujo) {
    return Trama{kHeaders, kFinCabeceras | kFinFlujo, flujo, codificar(get())};
}

Atender cuerpo_de(size_t n) {
    return [n](const cero::Peticion&) { return cero::Respuesta::texto(string(n, 'x')); };
}

struct Recibido {
    size_t datos = 0;
    int tramas = 0;
    bool fin = false;
    bool ping = false;
};

Recibido hasta_el_ping(Cliente& c, uint32_t flujo) {
    c.mandar(Trama{kPing, 0, 0, "12345678"});
    Recibido r;
    for (auto x = c.trama(); x; x = c.trama()) {
        if (x->tipo == kData && x->flujo == flujo) {
            r.datos += x->carga.size();
            ++r.tramas;
            r.fin = r.fin || x->fin_flujo();
        }
        if (x->tipo == kPing && (x->banderas & kReconoce) != 0) {
            r.ping = true;
            break;
        }
    }
    return r;
}

Recibido datos_hasta_el_fin(Cliente& c, uint32_t flujo) {
    Recibido r;
    for (auto x = c.trama(); x; x = c.trama()) {
        if (x->tipo == kData && x->flujo == flujo) {
            r.datos += x->carga.size();
            ++r.tramas;
            if (x->fin_flujo()) {
                r.fin = true;
                break;
            }
        }
    }
    return r;
}

size_t primer_data(Cliente& c, uint32_t flujo) {
    const auto x = c.hasta(kData);
    return x && x->flujo == flujo ? x->carga.size() : 0;
}

optional<Error> codigo_de(const optional<Trama>& x) {
    if (!x) return nullopt;
    const size_t donde = x->tipo == kGoaway ? 4 : 0;
    return static_cast<Error>(entero32(x->carga, donde));
}

PRUEBA(h2_040_las_ventanas_reservan_lo_que_cabe) {
    Ventanas v{10};
    v.abrir(1);
    COMPRUEBA(v.reservar(1, 4, kSiempre) == size_t{4}, "H2-040: pide menos de lo que hay");
    COMPRUEBA(v.reservar(1, 100, kSiempre) == size_t{6}, "H2-040: el flujo pone el tope");
    COMPRUEBA(v.ventana(1) == 0 && v.ventana(0) == 65'535 - 10, "H2-040: y se descuenta de los dos");
    COMPRUEBA(v.ampliar(1, 1'000'000), "crédito para el flujo");
    COMPRUEBA(v.reservar(1, 1'000'000, kSiempre) == size_t{65'535 - 10}, "H2-040: la conexión pone el tope");
    COMPRUEBA(v.ampliar(0, 0x7fff'ffff) && !v.ampliar(0, 1), "H2-041: la conexión no pasa de 2^31-1");
    COMPRUEBA(!v.ampliar(1, 0x7fff'ffff), "H2-041: ni el flujo");
    COMPRUEBA(v.ampliar(9, 0x7fff'ffff) && !v.ventana(9), "un flujo que ya no está se ignora");
    COMPRUEBA(!v.reajustar(0x7fff'ffff), "H2-041: ni con SETTINGS");
    COMPRUEBA(v.reajustar(-2'000'000) && v.ventana(1) < 0, "H2-040: la ventana puede quedar negativa");
}

PRUEBA(h2_040_quien_espera_ventana_despierta_al_soltar_o_cerrar) {
    for (const bool soltar : {true, false}) {
        Ventanas v{0};
        v.abrir(1);
        atomic<bool> vuelto{false};
        optional<size_t> dado{7};
        thread espera{[&] {
            dado = v.reservar(1, 10, kSiempre);
            vuelto = true;
        }};
        this_thread::sleep_for(50ms);
        COMPRUEBA(!vuelto.load(), "H2-040: sin ventana se espera");
        if (soltar) {
            v.soltar(1);
        } else {
            v.cerrar();
        }
        espera.join();
        COMPRUEBA(!dado.has_value(), "H2-040: un flujo anulado o una conexión cerrada no esperan más");
    }
}

PRUEBA(h2_052_quien_espera_ventana_tiene_plazo) {
    Ventanas v{0};
    v.abrir(1);
    const auto empezo = chrono::steady_clock::now();
    COMPRUEBA(!v.reservar(1, 10, empezo + 100ms), "H2-052: sin crédito en el plazo, no hay reserva");
    COMPRUEBA(chrono::steady_clock::now() - empezo < 1s, "H2-052: y no se espera más");
}

PRUEBA(h2_040_no_sale_data_sin_ventana_en_el_flujo) {
    auto [d, hilo] = conductor(cuerpo_de(100));
    {
        Cliente c{d};
        c.abrir_h2(ajuste(kVentanaInicial, 0));
        c.mandar(pide(1));
        const auto cabeceras = c.hasta(kHeaders);
        COMPRUEBA(cabeceras && !cabeceras->fin_flujo(), "H2-040: las cabeceras sí salen");
        const auto nada = hasta_el_ping(c, 1);
        COMPRUEBA(nada.ping && nada.datos == 0, "H2-040: con ventana cero no sale ningún DATA");

        c.mandar(Trama::ventana(1, 5));
        COMPRUEBA(primer_data(c, 1) == 5, "H2-040: sale justo lo que cabe");
        COMPRUEBA(hasta_el_ping(c, 1).datos == 0, "H2-040: y nada más");

        c.mandar(Trama::ventana(1, 95));
        const auto resto = datos_hasta_el_fin(c, 1);
        COMPRUEBA(resto.datos == 95 && resto.fin, "H2-040: y el resto cuando hay sitio");
    }
    hilo.join();
}

PRUEBA(h2_040_ni_mas_de_lo_que_cabe_en_la_conexion) {
    auto [d, hilo] = conductor(cuerpo_de(100'000));
    {
        Cliente c{d};
        c.abrir_h2(ajuste(kVentanaInicial, kTope));
        c.mandar(pide(1));
        size_t llegados = 0;
        while (llegados < 65'535) {
            const auto x = c.trama();
            if (!x) break;
            if (x->tipo == kData) llegados += x->carga.size();
        }
        const auto mas = hasta_el_ping(c, 1);
        COMPRUEBA(llegados + mas.datos == 65'535, "H2-040: la conexión empieza en 65 535");

        c.mandar(Trama::ventana(0, 100'000 - 65'535));
        const auto resto = datos_hasta_el_fin(c, 1);
        COMPRUEBA(resto.datos == 100'000 - 65'535 && resto.fin,
                  "H2-040: el WINDOW_UPDATE de la conexión deja salir el resto");
    }
    hilo.join();
}

PRUEBA(h2_040_la_ventana_inicial_mueve_los_flujos_abiertos) {
    auto [d, hilo] = conductor(cuerpo_de(100));
    {
        Cliente c{d};
        c.abrir_h2(ajuste(kVentanaInicial, 0));
        c.mandar(pide(1));
        c.hasta(kHeaders);

        c.mandar(Trama{kSettings, 0, 0, ajuste(kVentanaInicial, 10)});
        COMPRUEBA(primer_data(c, 1) == 10,
                  "H2-040: subir la ventana inicial sube la del flujo abierto");
        COMPRUEBA(hasta_el_ping(c, 1).datos == 0, "H2-040: y solo eso");

        c.mandar(Trama{kSettings, 0, 0, ajuste(kVentanaInicial, 5)});
        c.mandar(Trama::ventana(1, 8));
        COMPRUEBA(primer_data(c, 1) == 3, "H2-040: bajarla deja la ventana en negativo y se cuenta");
        COMPRUEBA(hasta_el_ping(c, 1).datos == 0, "H2-040: y no sale más");
    }
    hilo.join();
}

PRUEBA(h2_041_un_window_update_que_desborda_la_conexion) {
    auto [d, hilo] = conductor(cuerpo_de(10));
    {
        Cliente c{d};
        c.abrir_h2();
        c.mandar(Trama::ventana(0, kTope));
        const auto adios = c.hasta(kGoaway);
        COMPRUEBA(codigo_de(adios) == Error::ControlDeFlujo, "H2-041: en la conexión, GOAWAY");
    }
    hilo.join();
}

void sin_ventana_de_conexion(Cliente& c) {
    c.abrir_h2(ajuste(kVentanaInicial, 0));
    c.mandar(pide(1));
    c.mandar(pide(3));
    c.mandar(Trama::ventana(3, 65'535));
    size_t llegados = 0;
    while (llegados < 65'535) {
        const auto x = c.trama();
        if (!x) break;
        if (x->tipo == kData && x->flujo == 3) llegados += x->carga.size();
    }
}

PRUEBA(h2_041_un_window_update_que_desborda_un_flujo) {
    auto [d, hilo] = conductor(cuerpo_de(100'000));
    {
        Cliente c{d};
        sin_ventana_de_conexion(c);
        c.mandar(Trama::ventana(1, kTope));
        c.mandar(Trama::ventana(1, 1));
        const auto corte = c.hasta(kRstStream);
        COMPRUEBA(corte && corte->flujo == 1 && codigo_de(corte) == Error::ControlDeFlujo,
                  "H2-041: en un flujo, RST_STREAM");
        COMPRUEBA(hasta_el_ping(c, 1).ping, "H2-041: y la conexión sigue");
    }
    hilo.join();
}

PRUEBA(h2_041_unos_ajustes_que_desbordan_un_flujo) {
    auto [d, hilo] = conductor(cuerpo_de(100'000));
    {
        Cliente c{d};
        sin_ventana_de_conexion(c);
        c.mandar(Trama::ventana(1, kTope));
        c.mandar(Trama{kSettings, 0, 0, ajuste(kVentanaInicial, 1)});
        const auto adios = c.hasta(kGoaway);
        COMPRUEBA(codigo_de(adios) == Error::ControlDeFlujo, "H2-041: §6.9.2, GOAWAY");
    }
    hilo.join();
}

PRUEBA(h2_046_el_que_espera_credito_ocupa_su_hueco) {
    Ajustes a;
    a.max_flujos = 2;
    auto [d, hilo] = conductor(cuerpo_de(100), a);
    {
        Cliente c{d};
        c.abrir_h2(ajuste(kVentanaInicial, 0));
        c.mandar(pide(1));
        c.mandar(pide(3));
        c.mandar(pide(5));
        const auto corte = c.hasta(kRstStream);
        COMPRUEBA(corte && corte->flujo == 5 && codigo_de(corte) == Error::Rechazado,
                  "H2-046 y H2-040: el que espera ventana sigue en vuelo");
    }
    hilo.join();
}

PRUEBA(h2_040_el_que_espera_credito_no_frena_a_los_demas) {
    auto [d, hilo] = conductor(cuerpo_de(100));
    {
        Cliente c{d};
        c.abrir_h2(ajuste(kVentanaInicial, 0));
        c.mandar(pide(1));
        c.mandar(pide(3));
        c.mandar(Trama::ventana(3, 1'000));
        const auto tres = datos_hasta_el_fin(c, 3);
        COMPRUEBA(tres.datos == 100 && tres.fin, "H2-040 y H2-029: el flujo 3 sale entero");
        const auto uno = hasta_el_ping(c, 1);
        COMPRUEBA(uno.ping && uno.datos == 0, "H2-040: y el 1 sigue esperando sin tapar nada");
    }
    hilo.join();
}

PRUEBA(h2_046_un_cliente_en_el_tope_nunca_es_rechazado) {
    Ajustes a;
    a.max_flujos = 2;
    auto [d, hilo] = conductor(cuerpo_de(3), a);
    int rechazos = 0;
    bool adios = false;
    int respuestas = 0;
    {
        Cliente c{d};
        c.abrir_h2();
        constexpr int kTotal = 1'100;
        uint32_t siguiente = 1;
        int pedidas = 0;
        for (; pedidas < 2; ++pedidas, siguiente += 2) c.mandar(pide(siguiente));
        while (respuestas < kTotal) {
            const auto x = c.trama();
            if (!x) break;
            if (x->tipo == kGoaway) {
                adios = true;
                break;
            }
            const bool rechazo = x->tipo == kRstStream;
            if (rechazo) ++rechazos;
            if (rechazo || (x->tipo == kData && x->fin_flujo())) {
                ++respuestas;
                if (pedidas < kTotal) {
                    c.mandar(pide(siguiente));
                    siguiente += 2;
                    ++pedidas;
                }
            }
        }
    }
    hilo.join();
    COMPRUEBA(respuestas == 1'100, "H2-046: las 1100 peticiones tienen respuesta");
    COMPRUEBA(rechazos == 0, "H2-046: un cliente que respeta el tope no ve REFUSED_STREAM");
    COMPRUEBA(!adios, "H2-038: ni un GOAWAY por llevar mucho tiempo trabajando");
}

PRUEBA(h2_038_rechazar_por_el_tope_no_cuenta_como_corte) {
    Ajustes a;
    a.max_flujos = 1;
    Sesion s{a};
    s.topes.cortes = 5;
    (void)s.recibir(pide(1));
    bool siempre = true;
    for (uint32_t id = 3; id < 60; id += 2) {
        const auto r = s.recibir(pide(id));
        siempre = siempre && r && r->cortado && r->cortado->codigo == Error::Rechazado;
    }
    COMPRUEBA(siempre, "H2-038: el REFUSED_STREAM es nuestro y no cuenta contra el cliente");
}

PRUEBA(h2_038_una_descarga_larga_no_choca_con_el_tope) {
    auto s = Sesion{Ajustes{}};
    s.topes.control_sin_flujo = 20;
    (void)s.recibir(pide(1));
    bool siempre = true;
    for (int i = 0; i < 200; ++i) {
        siempre = siempre && s.recibir(Trama::ventana(0, 1'000)).has_value();
    }
    COMPRUEBA(siempre, "H2-038: los WINDOW_UPDATE de una descarga en curso no cuentan");

    auto vacia = Sesion{Ajustes{}};
    vacia.topes.control_sin_flujo = 20;
    optional<Error> fallo;
    for (int i = 0; i < 200 && !fallo; ++i) {
        const auto r = vacia.recibir(Trama::ventana(0, 1));
        if (!r) fallo = r.error().codigo;
    }
    COMPRUEBA(fallo == Error::Calma, "H2-038: sin ningún flujo vivo, sí");
}

}

PRUEBAS_MAIN
