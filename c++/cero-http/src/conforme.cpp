#include <charconv>
#include <chrono>
#include <string>
#include <string_view>
#include <system_error>
#include <utility>

#include "cero/servidor.hpp"

using namespace std;
using namespace cero;

int main(int argc, char** argv) {
    unsigned short puerto = 8777;
    if (argc > 1) {
        const string_view arg{argv[1]};
        from_chars(arg.data(), arg.data() + arg.size(), puerto);
    }
    Plazos plazos;
    if (argc > 2) {
        const string_view arg{argv[2]};
        long long ms = 0;
        if (from_chars(arg.data(), arg.data() + arg.size(), ms).ec != errc{}) return 2;
        const chrono::milliseconds plazo{ms};
        plazos = {plazo, plazo, 2 * plazo};
    }

    Router router;
    if (!router.ruta("GET", "/", "raiz") || !router.ruta("GET", "/eco", "eco") ||
        !router.ruta("POST", "/eco", "eco") || !router.ruta("GET", "/grande", "grande") ||
        !router.ruta("GET", "/descarga", "descarga")) {
        return 1;
    }
    return Servidor{std::move(router)}
        .accion("raiz", [](const Contexto&) { return Respuesta::texto("cero, conforme\n"); })
        .accion("eco", [](const Contexto& c) { return Respuesta::texto(c.peticion.cuerpo); })
        .accion("grande", [](const Contexto&) {
            return Respuesta::texto(string(1 << 20, 'x'));
        })
        .accion("descarga", [](const Contexto&) {
            return Respuesta::texto(string(32 << 20, 'x'));
        })
        .plazos(plazos)
        .direccion("127.0.0.1")
        .escuchar(puerto);
}
