#include <atomic>
#include <chrono>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "cero/dependencias.hpp"
#include "cero/fallo.hpp"
#include "prueba.hpp"

using namespace std;
using namespace cero;

namespace {

struct Huevo;
struct Gallina {
    shared_ptr<Huevo> de;
};
struct Huevo {
    shared_ptr<Gallina> de;
};

struct Reloj {
    int hora = 12;
};
struct Agenda {
    shared_ptr<Reloj> reloj;
};

optional<Fallo> fallo_al_pedir_gallina(Dependencias& d) {
    try {
        (void)d.obtener<Gallina>();
    } catch (const Fallo& f) {
        return f;
    }
    return nullopt;
}

void con_ciclo(Dependencias& d) {
    d.registrar<Gallina>([](Dependencias& r) {
        return make_shared<Gallina>(Gallina{r.obtener<Huevo>()});
    });
    d.registrar<Huevo>([](Dependencias& r) {
        return make_shared<Huevo>(Huevo{r.obtener<Gallina>()});
    });
}

PRUEBA(rut_035_un_ciclo_se_detecta_y_se_dice) {
    Dependencias d;
    con_ciclo(d);
    const auto f = fallo_al_pedir_gallina(d);
    COMPRUEBA(f.has_value(), "RUT-035: falla en vez de colgarse o desbordar la pila");
    COMPRUEBA(f && f->mensaje.find("ciclo") != string::npos, "RUT-035: y dice que es un ciclo");
    COMPRUEBA(f && f->estado == 500, "RUT-035: que es un error del programa, no del cliente");
}

PRUEBA(rut_035_tras_el_ciclo_el_contenedor_sigue_sirviendo) {
    Dependencias d;
    con_ciclo(d);
    (void)fallo_al_pedir_gallina(d);
    const auto otra_vez = fallo_al_pedir_gallina(d);
    COMPRUEBA(otra_vez && otra_vez->mensaje.find("ciclo") != string::npos,
              "RUT-035: el segundo intento ve el mismo ciclo, no una pila sucia");
}

PRUEBA(una_cadena_sin_ciclo_se_resuelve_una_vez) {
    Dependencias d;
    int construidos = 0;
    d.registrar<Reloj>([&construidos](Dependencias&) {
        ++construidos;
        return make_shared<Reloj>();
    });
    d.registrar<Agenda>([](Dependencias& r) {
        return make_shared<Agenda>(Agenda{r.obtener<Reloj>()});
    });
    const auto a = d.obtener<Agenda>();
    COMPRUEBA(a->reloj == d.obtener<Reloj>(), "la misma instancia en toda la cadena");
    COMPRUEBA(construidos == 1, "y se construyó una sola vez");
}

PRUEBA(a_la_vez_la_fabrica_corre_una_sola_vez) {
    Dependencias d;
    atomic<int> construidos = 0;
    d.registrar<Reloj>([&construidos](Dependencias&) {
        ++construidos;
        this_thread::sleep_for(chrono::milliseconds{50});
        return make_shared<Reloj>();
    });
    vector<shared_ptr<Reloj>> vistos(8);
    {
        vector<jthread> hilos;
        for (auto& visto : vistos) hilos.emplace_back([&d, &visto] { visto = d.obtener<Reloj>(); });
    }
    COMPRUEBA(construidos == 1, "la fábrica corrió " + to_string(construidos.load()) + " veces");
    for (const auto& v : vistos) COMPRUEBA(v == vistos.front(), "y todos ven la misma");
}

PRUEBA(registrar_mientras_otro_resuelve_no_rompe_nada) {
    Dependencias d;
    d.registrar<Reloj>([](Dependencias&) { return make_shared<Reloj>(); });
    jthread lector{[&d] {
        for (int i = 0; i < 2000; ++i) (void)d.obtener<Reloj>();
    }};
    for (int i = 0; i < 2000; ++i) {
        d.registrar<Agenda>(
            [](Dependencias& r) { return make_shared<Agenda>(Agenda{r.obtener<Reloj>()}); });
    }
    lector.join();
    COMPRUEBA(d.obtener<Agenda>()->reloj == d.obtener<Reloj>(), "el registro sigue entero");
}

PRUEBA(lo_no_registrado_falla) {
    Dependencias d;
    bool fallo = false;
    try {
        (void)d.obtener<Reloj>();
    } catch (const Fallo&) {
        fallo = true;
    }
    COMPRUEBA(fallo, "no se construye en silencio");
}

}

PRUEBAS_MAIN
