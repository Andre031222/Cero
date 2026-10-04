#pragma once

#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace cero {

// Las cabeceras viajan como pares y en orden: dos respuestas iguales dan los mismos octetos, que
// es lo que permite compararlas y cachearlas.
using Pares = std::vector<std::pair<std::string, std::string>>;

struct Respuesta {
    unsigned estado = 200;
    std::string tipo = "text/plain; charset=utf-8";
    std::string cuerpo;
    Pares extra;

    static Respuesta texto(std::string_view cuerpo);
    static Respuesta json(std::string_view ya_formado);
    // RUT-022: la descarga sanea el nombre antes de ponerlo en la cabecera. No es opcional: por
    // ahí se intentó colar una cookie.
    static Respuesta descarga(std::string cuerpo, std::string_view nombre, std::string_view tipo);
    // RUT-020: devolver nada responde 204. RUT-021: la redirección es 302 con `Location`.
    static Respuesta nada();
    static Respuesta redirigir(std::string_view a);
    static Respuesta html(std::string_view cuerpo);
    static Respuesta codigo(unsigned estado, std::string_view cuerpo = {});

    Respuesta&& cabecera(std::string_view nombre, std::string_view valor) &&;
};

std::string_view razon(unsigned estado);

}  // namespace cero
