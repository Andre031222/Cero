#include <memory>
#include <optional>
#include <string>

#include "cero/dependencias.hpp"
#include "cero/fallo.hpp"
#include "prueba.hpp"

using namespace std;

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

optional<cero::Fallo> fallo_al_pedir_gallina(cero::Dependencias& d) {
    try {
        (void)d.obtener<Gallina>();
    } catch (const cero::Fallo& f) {
        return f;
    }
    return nullopt;
}

void con_ciclo(cero::Dependencias& d) {
    d.registrar<Gallina>([](cero::Dependencias& r) {
        return make_shared<Gallina>(Gallina{r.obtener<Huevo>()});
    });
    d.registrar<Huevo>([](cero::Dependencias& r) {
        return make_shared<Huevo>(Huevo{r.obtener<Gallina>()});
    });
}

PRUEBA(rut_035_un_ciclo_se_detecta_y_se_dice) {
    cero::Dependencias d;
    con_ciclo(d);
    const auto f = fallo_al_pedir_gallina(d);
    COMPRUEBA(f.has_value(), "RUT-035: falla en vez de colgarse o desbordar la pila");
    COMPRUEBA(f && f->mensaje.find("ciclo") != string::npos, "RUT-035: y dice que es un ciclo");
    COMPRUEBA(f && f->estado == 500, "RUT-035: que es un error del programa, no del cliente");
}

PRUEBA(rut_035_tras_el_ciclo_el_contenedor_sigue_sirviendo) {
    cero::Dependencias d;
    con_ciclo(d);
    (void)fallo_al_pedir_gallina(d);
    const auto otra_vez = fallo_al_pedir_gallina(d);
    COMPRUEBA(otra_vez && otra_vez->mensaje.find("ciclo") != string::npos,
              "RUT-035: el segundo intento ve el mismo ciclo, no una pila sucia");
}

PRUEBA(una_cadena_sin_ciclo_se_resuelve_una_vez) {
    cero::Dependencias d;
    int construidos = 0;
    d.registrar<Reloj>([&construidos](cero::Dependencias&) {
        ++construidos;
        return make_shared<Reloj>();
    });
    d.registrar<Agenda>([](cero::Dependencias& r) {
        return make_shared<Agenda>(Agenda{r.obtener<Reloj>()});
    });
    const auto a = d.obtener<Agenda>();
    COMPRUEBA(a->reloj == d.obtener<Reloj>(), "la misma instancia en toda la cadena");
    COMPRUEBA(construidos == 1, "y se construyó una sola vez");
}

PRUEBA(lo_no_registrado_falla) {
    cero::Dependencias d;
    bool fallo = false;
    try {
        (void)d.obtener<Reloj>();
    } catch (const cero::Fallo&) {
        fallo = true;
    }
    COMPRUEBA(fallo, "no se construye en silencio");
}

}

PRUEBAS_MAIN
