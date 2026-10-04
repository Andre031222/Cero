#pragma once

#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace cero {

struct Respuesta {
    unsigned estado = 200;
    std::string tipo = "text/plain; charset=utf-8";
    std::string cuerpo;
    std::vector<std::pair<std::string, std::string>> extra;

    static Respuesta texto(std::string_view cuerpo);
    static Respuesta json(std::string_view ya_formado);
    static Respuesta html(std::string_view cuerpo);
    static Respuesta codigo(unsigned estado, std::string_view cuerpo = {});

    Respuesta&& cabecera(std::string_view nombre, std::string_view valor) &&;
};

std::string_view razon(unsigned estado);

}  // namespace cero
