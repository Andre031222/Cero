#include "cero/peticion.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <charconv>
#include <cstdint>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

namespace cero {
namespace {

constexpr std::array<std::string_view, 9> kMetodos{"GET",     "HEAD",  "POST",  "PUT",    "DELETE",
                                                   "OPTIONS", "PATCH", "TRACE", "CONNECT"};
constexpr std::size_t kMaxLinea = 8 * 1024;
constexpr std::size_t kMaxCabeceras = 100;

using Fallo = std::unexpected<Rechazo>;

bool es_token(unsigned char c) {
    constexpr std::string_view otros = "!#$%&'*+-.^_`|~";
    return std::isalnum(c) != 0 || otros.find(static_cast<char>(c)) != std::string_view::npos;
}

std::string minuscula(std::string_view s) {
    std::string r(s);
    std::ranges::transform(r, r.begin(), [](unsigned char c) { return std::tolower(c); });
    return r;
}

std::string_view recortar(std::string_view s) {
    constexpr std::string_view blancos = " \t";
    const auto desde = s.find_first_not_of(blancos);
    if (desde == std::string_view::npos) return {};
    return s.substr(desde, s.find_last_not_of(blancos) - desde + 1);
}

// El RFC 9112 §2.2 pide CRLF y aceptar LF suelto es la tolerancia habitual. Un CR suelto *dentro*
// de la línea no se tolera: es la vía del contrabando cuando hay un intermediario que sí lo parte.
std::expected<std::string, Rechazo> linea(Lector& lector) {
    std::string crudo;
    for (int c = lector.octeto(); c != '\n'; c = lector.octeto()) {
        if (c < 0) return Fallo{Rechazo::MalFormada};
        if (crudo.size() >= kMaxLinea) return Fallo{Rechazo::Demasiado};  // HTTP-023
        crudo.push_back(static_cast<char>(c));
    }
    if (crudo.ends_with('\r')) crudo.pop_back();
    if (crudo.contains('\r')) return Fallo{Rechazo::MalFormada};
    return crudo;
}

std::expected<void, Rechazo> partir_inicial(std::string_view l, Peticion& p) {
    const auto uno = l.find(' ');
    const auto dos = uno == std::string_view::npos ? uno : l.find(' ', uno + 1);
    // HTTP-004: tres partes exactas. Ni dos, ni cuatro: una línea sin versión es 400 y no 505.
    if (uno == std::string_view::npos || dos == std::string_view::npos ||
        l.find(' ', dos + 1) != std::string_view::npos || uno == 0 || dos == uno + 1 ||
        dos + 1 == l.size()) {
        return Fallo{Rechazo::MalFormada};
    }
    p.metodo = l.substr(0, uno);
    p.destino = l.substr(uno + 1, dos - uno - 1);
    p.version = l.substr(dos + 1);

    // HTTP-007: los métodos distinguen mayúsculas. Tratar `get` como `GET` es lo que deja que un
    // intermediario y nosotros leamos cosas distintas de los mismos octetos.
    if (std::ranges::find(kMetodos, p.metodo) == kMetodos.end()) {
        return Fallo{Rechazo::NoImplementado};
    }
    if (p.version == "HTTP/1.1" || p.version == "HTTP/1.0") return {};
    if (p.version.starts_with("HTTP/")) return Fallo{Rechazo::VersionNoSoportada};
    return Fallo{Rechazo::MalFormada};
}

std::expected<void, Rechazo> leer_cabeceras(Lector& lector, Peticion& p) {
    for (std::size_t vistas = 0;; ++vistas) {
        const auto l = linea(lector);
        if (!l) return Fallo{l.error()};
        if (l->empty()) return {};
        if (vistas >= kMaxCabeceras) return Fallo{Rechazo::Demasiado};

        // HTTP-009: la cabecera plegada la retiró el RFC 9112 §5.2 porque los intermediarios la
        // despliegan de formas distintas.
        if (l->starts_with(' ') || l->starts_with('\t')) return Fallo{Rechazo::MalFormada};

        const auto corte = l->find(':');
        if (corte == std::string::npos || corte == 0) return Fallo{Rechazo::MalFormada};
        const std::string_view nombre{l->data(), corte};
        const std::string_view valor = std::string_view{*l}.substr(corte + 1);

        // HTTP-008 y HTTP-010: ni espacio antes de los dos puntos, ni nada fuera del token.
        if (!std::ranges::all_of(nombre, [](char c) { return es_token(static_cast<unsigned char>(c)); })) {
            return Fallo{Rechazo::MalFormada};
        }
        // HTTP-011: nulos y controles fuera.
        if (std::ranges::any_of(valor, [](char ch) {
                const auto c = static_cast<unsigned char>(ch);
                return c == 0 || (c < 0x20 && c != '\t') || c == 0x7f;
            })) {
            return Fallo{Rechazo::MalFormada};
        }

        const std::string clave = minuscula(nombre);
        const std::string limpio{recortar(valor)};  // HTTP-012
        const auto previo = p.cabeceras.find(clave);
        if (previo == p.cabeceras.end()) {
            p.cabeceras.emplace(clave, limpio);
            continue;
        }
        // HTTP-014 y HTTP-016: `Host` repetido y `Content-Length` discrepante se rechazan; las
        // demás se unen con coma, como manda el RFC 9110 §5.3.
        if (clave == "host") return Fallo{Rechazo::MalFormada};
        if (clave == "content-length") {
            if (previo->second != limpio) return Fallo{Rechazo::MalFormada};
            continue;
        }
        previo->second += ", " + limpio;
    }
}

std::expected<std::string, Rechazo> exactos(Lector& lector, std::size_t cuantos) {
    std::string datos;
    datos.reserve(cuantos);
    for (std::size_t i = 0; i < cuantos; ++i) {
        const int c = lector.octeto();
        if (c < 0) return Fallo{Rechazo::MalFormada};
        datos.push_back(static_cast<char>(c));
    }
    return datos;
}

std::expected<std::string, Rechazo> por_trozos(Lector& lector) {
    std::string cuerpo;
    while (true) {
        const auto cabecera = linea(lector);
        if (!cabecera) return Fallo{cabecera.error()};
        const std::string_view medida = recortar(std::string_view{*cabecera}.substr(
            0, std::min(cabecera->find(';'), cabecera->size())));

        // HTTP-021: un tamaño que no es hexadecimal se rechaza.
        std::size_t tamano = 0;
        const auto fin = medida.data() + medida.size();
        const auto leido = std::from_chars(medida.data(), fin, tamano, 16);
        if (leido.ec != std::errc{} || leido.ptr != fin) return Fallo{Rechazo::MalFormada};

        if (tamano == 0) {
            for (auto t = linea(lector); !t || !t->empty(); t = linea(lector)) {
                if (!t) return Fallo{t.error()};
            }
            return cuerpo;
        }
        const auto trozo = exactos(lector, tamano);
        if (!trozo) return Fallo{trozo.error()};
        cuerpo += *trozo;
        const auto cierre = linea(lector);
        if (!cierre) return Fallo{cierre.error()};
        if (!cierre->empty()) return Fallo{Rechazo::MalFormada};
    }
}

std::expected<void, Rechazo> leer_cuerpo(Lector& lector, Peticion& p) {
    const auto te = p.cabecera("transfer-encoding");
    const auto cl = p.cabecera("content-length");
    if (te) {
        // Las dos juntas declaran dos longitudes del mismo cuerpo. Si nosotros creemos a una y el
        // intermediario a la otra, leemos dos peticiones distintas de los mismos octetos: es el
        // contrabando de peticiones, y el RFC 9112 §6.3 manda rechazar.
        if (cl) return Fallo{Rechazo::MalFormada};
        // HTTP-020: `chunked` tiene que ser la última codificación de la lista.
        const auto coma = te->rfind(',');
        const auto ultima = recortar(coma == std::string_view::npos ? *te : te->substr(coma + 1));
        if (minuscula(ultima) != "chunked") return Fallo{Rechazo::MalFormada};
        auto cuerpo = por_trozos(lector);
        if (!cuerpo) return Fallo{cuerpo.error()};
        p.cuerpo = std::move(*cuerpo);
        return {};
    }
    if (!cl) return {};

    // HTTP-018 y HTTP-019: negativo o no numérico. Leerlo sin signo cubre los dos, porque el `-`
    // no es un dígito y `from_chars` se para antes de consumirlo todo.
    std::uint64_t largo = 0;
    const auto fin = cl->data() + cl->size();
    const auto leido = std::from_chars(cl->data(), fin, largo);
    if (leido.ec != std::errc{} || leido.ptr != fin) return Fallo{Rechazo::MalFormada};

    auto cuerpo = exactos(lector, static_cast<std::size_t>(largo));
    if (!cuerpo) return Fallo{cuerpo.error()};
    p.cuerpo = std::move(*cuerpo);
    return {};
}

}  // namespace

std::optional<std::string_view> Peticion::cabecera(std::string_view nombre) const {
    const auto i = cabeceras.find(minuscula(nombre));
    if (i == cabeceras.end()) return std::nullopt;
    return i->second;
}

std::string_view Peticion::camino() const {
    std::string_view d{destino};
    d = d.substr(0, std::min(d.find('?'), d.size()));
    // HTTP-002: la forma absoluta es válida en una petición normal, no solo hacia un proxy.
    for (std::string_view esquema : {"http://", "https://"}) {
        if (!d.starts_with(esquema)) continue;
        d.remove_prefix(esquema.size());
        const auto barra = d.find('/');
        return barra == std::string_view::npos ? std::string_view{"/"} : d.substr(barra);
    }
    return d;
}

std::expected<Peticion, Rechazo> leer(Lector& lector) {
    Peticion p;
    const auto inicial = linea(lector);
    if (!inicial) return Fallo{inicial.error()};
    if (auto r = partir_inicial(*inicial, p); !r) return Fallo{r.error()};
    if (auto r = leer_cabeceras(lector, p); !r) return Fallo{r.error()};
    // HTTP-013 y HTTP-015: HTTP/1.1 exige `Host`; HTTP/1.0 puede no traerlo.
    if (p.version == "HTTP/1.1" && !p.cabecera("host")) return Fallo{Rechazo::MalFormada};
    if (auto r = leer_cuerpo(lector, p); !r) return Fallo{r.error()};
    return p;
}

}  // namespace cero
