// El servidor contra el que corre el banco de `spec/banco/`.
//
// `/eco` lee el cuerpo: sin eso los vectores de encuadre no comprueban nada, porque un servidor
// que responde sin leerlo pasa por válido sin haber parseado.

#include <charconv>
#include <string_view>

#include "cero/servidor.hpp"

int main(int argc, char** argv) {
    unsigned short puerto = 8777;
    if (argc > 1) {
        const std::string_view arg{argv[1]};
        std::from_chars(arg.data(), arg.data() + arg.size(), puerto);
    }

    cero::Router router;
    if (!router.ruta("GET", "/", "raiz") || !router.ruta("GET", "/eco", "eco") ||
        !router.ruta("POST", "/eco", "eco")) {
        return 1;
    }
    return cero::Servidor{std::move(router)}
        .accion("raiz", [](const cero::Contexto&) { return cero::Respuesta::texto("cero, conforme\n"); })
        .accion("eco", [](const cero::Contexto& c) { return cero::Respuesta::texto(c.peticion.cuerpo); })
        .escuchar(puerto);
}
