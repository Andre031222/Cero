#include <unistd.h>

#include <chrono>
#include <filesystem>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "cero/datos/disco.hpp"
#include "cero/servidor.hpp"
#include "prueba.hpp"

using namespace std;
using namespace std::chrono_literals;

namespace {

filesystem::path carpeta(string_view nombre) {
    auto c = filesystem::temp_directory_path() /
             ("cero-sesiones-" + to_string(::getpid()) + "-" + string{nombre});
    filesystem::remove_all(c);
    return c;
}

size_t archivos(const filesystem::path& c) {
    size_t n = 0;
    for (const auto& e : filesystem::directory_iterator{c}) n += e.is_regular_file() ? 1 : 0;
    return n;
}

PRUEBA(ses_012_dos_almacenes_sobre_la_misma_carpeta_ven_lo_mismo) {
    const auto c = carpeta("compartida");
    cero::AlmacenEnDisco una{c, 600s, nullopt};
    cero::AlmacenEnDisco otra{c, 600s, nullopt};

    const auto g = *una.crear();
    {
        const auto tomado = g.tomar();
        g.sesion->poner("usuario", "ana");
    }
    una.guardar(g);

    const auto vista = otra.recuperar(g.sesion->id());
    COMPRUEBA(vista.has_value(), "SES-012");
    COMPRUEBA(vista && vista->sesion->leer("usuario") == "ana", "SES-012");
    COMPRUEBA(vista && !vista->sesion->cookie_pendiente(),
              "SES-008: la que llega de otra instancia no reemite la cookie");
    filesystem::remove_all(c);
}

PRUEBA(ses_012_dos_servidores_que_no_comparten_memoria_ven_la_misma_sesion) {
    const auto c = carpeta("servidores");
    const auto montar = [&c] {
        cero::Router r;
        (void)r.ruta("GET", "/entrar", "entrar");
        (void)r.ruta("GET", "/quien", "quien");
        return cero::Servidor{std::move(r)}
            .sesiones(make_shared<cero::AlmacenEnDisco>(c, 600s, nullopt))
            .accion("entrar",
                    [](const cero::Contexto& x) {
                        const auto g = x.abrir_sesion();
                        const auto tomado = g->tomar();
                        g->sesion->poner("usuario", "ana");
                        return string{"dentro"};
                    })
            .accion("quien", [](const cero::Contexto& x) {
                const auto& g = x.sesion();
                if (!g) return string{"nadie"};
                const auto tomado = g->tomar();
                return string{g->sesion->leer("usuario").value_or("nadie")};
            });
    };
    const auto una = montar();
    const auto otra = montar();

    cero::Peticion p;
    p.metodo = "GET";
    p.version = "HTTP/1.1";
    p.destino = "/entrar";
    string cookie;
    for (const auto& [n, v] : una.responder(p).extra) {
        if (n == "Set-Cookie") cookie = v.substr(0, v.find(';'));
    }
    p.destino = "/quien";
    p.cabeceras.emplace("cookie", cookie);
    COMPRUEBA(otra.responder(p).cuerpo == "ana", "SES-012: " + cookie);
    filesystem::remove_all(c);
}

PRUEBA(ses_001_sobre_disco_leer_no_crea) {
    const auto c = carpeta("leer");
    cero::AlmacenEnDisco a{c, 600s, nullopt};
    COMPRUEBA(!a.recuperar(nullopt).has_value(), "SES-001");
    COMPRUEBA(!a.recuperar(string(64, 'a')).has_value(), "SES-001");
    COMPRUEBA(a.cuantas() == 0 && archivos(c) == 0, "SES-001: y no dejó nada escrito");
    filesystem::remove_all(c);
}

PRUEBA(un_identificador_que_no_lo_es_no_sale_de_la_carpeta) {
    const auto c = carpeta("camino");
    const auto ajena = carpeta("ajena");
    cero::AlmacenEnDisco a{c, 600s, nullopt};
    cero::AlmacenEnDisco otra{ajena, 600s, nullopt};
    const auto suya = otra.crear()->sesion->id();
    const auto escapada = "../" + ajena.filename().string() + "/" + suya;
    COMPRUEBA(!a.recuperar(escapada).has_value(), "no se lee una sesión de fuera: " + escapada);
    for (const auto& malo : vector<string>{"/etc/passwd", "", string(64, 'g'), string(63, 'a')}) {
        COMPRUEBA(!a.recuperar(malo).has_value(), "rechazado: " + malo);
    }
    filesystem::remove_all(c);
    filesystem::remove_all(ajena);
}

PRUEBA(ses_005_y_006_rotar_sobre_disco) {
    const auto c = carpeta("rotar");
    cero::AlmacenEnDisco a{c, 600s, nullopt};
    const auto g = *a.crear();
    {
        const auto tomado = g.tomar();
        g.sesion->poner("usuario", "ana");
    }
    a.guardar(g);
    const auto viejo = g.sesion->id();

    const auto nuevo = a.rotar(g);
    COMPRUEBA(nuevo && *nuevo != viejo, "SES-005");
    COMPRUEBA(archivos(c) == 1, "SES-005: la vieja se borra, no se acumula");
    const auto vista = a.recuperar(nuevo.value_or(""));
    COMPRUEBA(vista && vista->sesion->leer("usuario") == "ana", "SES-005: con sus atributos");
    COMPRUEBA(!a.recuperar(viejo).has_value(), "SES-005: y el viejo ya no vale");

    {
        const auto tomado = g.tomar();
        g.sesion->invalidar();
    }
    COMPRUEBA(!a.rotar(g).has_value(), "SES-006");
    filesystem::remove_all(c);
}

PRUEBA(invalidar_borra_el_archivo) {
    const auto c = carpeta("invalidar");
    cero::AlmacenEnDisco a{c, 600s, nullopt};
    const auto g = *a.crear();
    COMPRUEBA(archivos(c) == 1, "crear ya escribe");
    {
        const auto tomado = g.tomar();
        g.sesion->invalidar();
    }
    a.guardar(g);
    COMPRUEBA(archivos(c) == 0, "y invalidar borra");
    filesystem::remove_all(c);
}

PRUEBA(una_sesion_caducada_se_borra_al_tocarla) {
    const auto c = carpeta("caducada");
    cero::AlmacenEnDisco a{c, 0s, nullopt};
    const auto id = a.crear()->sesion->id();
    this_thread::sleep_for(5ms);
    COMPRUEBA(!a.recuperar(id).has_value(), "caducada por inactividad");
    COMPRUEBA(archivos(c) == 0, "y el archivo ya no está");
    filesystem::remove_all(c);
}

PRUEBA(la_carpeta_y_los_archivos_solo_los_lee_su_dueno) {
    const auto c = carpeta("permisos");
    cero::AlmacenEnDisco a{c, 600s, nullopt};
    (void)a.crear();
    const auto ajenos = filesystem::perms::group_all | filesystem::perms::others_all;
    COMPRUEBA((filesystem::status(c).permissions() & ajenos) == filesystem::perms::none,
              "la carpeta es 0700");
    for (const auto& e : filesystem::directory_iterator{c}) {
        COMPRUEBA((e.status().permissions() & ajenos) == filesystem::perms::none, "y cada archivo, 0600");
    }
    filesystem::remove_all(c);
}

}

PRUEBAS_MAIN
