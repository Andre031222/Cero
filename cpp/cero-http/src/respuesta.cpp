#include "cero/respuesta.hpp"

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
