#include <string>
#include <string_view>
#include <utility>

#include "cero/respuesta.hpp"

#include "cero/seguridad.hpp"
#include "cero/formato.hpp"

namespace cero {

Respuesta Respuesta::texto(std::string_view cuerpo) {
    return {200, "text/plain; charset=utf-8", std::string{cuerpo}, {}};
}

Respuesta Respuesta::json(std::string_view ya_formado) {
    return {200, "application/json; charset=utf-8", std::string{ya_formado}, {}};
}

Respuesta Respuesta::html(std::string_view cuerpo) {
    return {200, "text/html; charset=utf-8", std::string{cuerpo}, {}};
}

Respuesta Respuesta::codigo(unsigned estado, std::string_view cuerpo) {
    return {estado, "text/plain; charset=utf-8", std::string{cuerpo}, {}};
}

Respuesta Respuesta::descarga(std::string cuerpo, std::string_view nombre,
                             std::string_view tipo) {
    Respuesta r{200, std::string{tipo}, std::move(cuerpo), {}};
    r.extra.emplace_back("Content-Disposition",
                         formato("attachment; filename=\"{}\"", sanear_nombre(nombre)));
    return r;
}

Respuesta Respuesta::nada() { return {204, "text/plain; charset=utf-8", {}, {}}; }

Respuesta Respuesta::redirigir(std::string_view a) {
    Respuesta r{302, "text/plain; charset=utf-8", {}, {}};
    r.extra.emplace_back("Location", std::string{a});
    return r;
}

Respuesta&& Respuesta::cabecera(std::string_view nombre, std::string_view valor) && {
    extra.emplace_back(nombre, valor);
    return std::move(*this);
}

std::string_view razon(unsigned estado) {
    switch (estado) {
        case 200: return "OK";
        case 204: return "No Content";
        case 302: return "Found";
        case 400: return "Bad Request";
        case 403: return "Forbidden";
        case 404: return "Not Found";
        case 405: return "Method Not Allowed";
        case 429: return "Too Many Requests";
        case 431: return "Request Header Fields Too Large";
        case 500: return "Internal Server Error";
        case 501: return "Not Implemented";
        case 503: return "Service Unavailable";
        case 505: return "HTTP Version Not Supported";
        default: return "";
    }
}

}  // namespace cero
