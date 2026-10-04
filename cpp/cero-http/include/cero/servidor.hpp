#pragma once

#include <functional>
#include <map>
#include <string>
#include <string_view>

#include "cero/peticion.hpp"
#include "cero/respuesta.hpp"
#include "cero/ruta.hpp"

namespace cero {

struct Contexto {
    const Peticion& peticion;
    const Captura& variables;

    std::optional<std::string_view> variable(std::string_view nombre) const;
};

class Servidor {
public:
    explicit Servidor(Router router) : router_(std::move(router)) {}

    Servidor&& accion(std::string_view nombre, std::function<Respuesta(const Contexto&)> f) &&;

    // El pipeline **sin socket**: se le da una petición y devuelve la respuesta. Es lo que hace
    // probable el ruteo sin abrir un puerto, y lo que permite montar Cero dentro de otra cosa.
    Respuesta responder(const Peticion& peticion) const;

    int escuchar(unsigned short puerto) const;

private:
    Router router_;
    std::map<std::string, std::function<Respuesta(const Contexto&)>, std::less<>> acciones_;
};

}  // namespace cero
