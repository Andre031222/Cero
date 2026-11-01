#include <charconv>
#include <chrono>
#include <string>
#include <string_view>
#include <system_error>
#include <utility>

#include "cero/servidor.hpp"

int main(int argc, char** argv) {
    unsigned short puerto = 8777;
    if (argc > 1) {
        const std::string_view arg{argv[1]};
        std::from_chars(arg.data(), arg.data() + arg.size(), puerto);
    }
    cero::Plazos plazos;
    if (argc > 2) {
        const std::string_view arg{argv[2]};
        long long ms = 0;
        if (std::from_chars(arg.data(), arg.data() + arg.size(), ms).ec != std::errc{}) return 2;
        const std::chrono::milliseconds plazo{ms};
        plazos = {plazo, plazo, 2 * plazo};
    }

    cero::Router router;
    if (!router.ruta("GET", "/", "raiz") || !router.ruta("GET", "/eco", "eco") ||
        !router.ruta("POST", "/eco", "eco") || !router.ruta("GET", "/grande", "grande")) {
        return 1;
    }
    return cero::Servidor{std::move(router)}
        .accion("raiz", [](const cero::Contexto&) { return cero::Respuesta::texto("cero, conforme\n"); })
        .accion("eco", [](const cero::Contexto& c) { return cero::Respuesta::texto(c.peticion.cuerpo); })
        .accion("grande", [](const cero::Contexto&) {
            return cero::Respuesta::texto(std::string(1 << 20, 'x'));
        })
        .plazos(plazos)
        .escuchar(puerto);
}
