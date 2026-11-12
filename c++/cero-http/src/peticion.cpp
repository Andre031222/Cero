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

#include "cero/formato.hpp"

using namespace std;

namespace cero {
namespace {

constexpr array<string_view, 9> kMetodos{"GET",     "HEAD",  "POST",  "PUT",    "DELETE",
                                         "OPTIONS", "PATCH", "TRACE", "CONNECT"};
constexpr size_t kMaxLinea = 8 * 1024;
constexpr size_t kMaxCabeceras = 100;

using Fallo = unexpected<Rechazo>;

bool es_token(unsigned char c) {
    constexpr string_view otros = "!#$%&'*+-.^_`|~";
    return isalnum(c) != 0 || otros.find(static_cast<char>(c)) != string_view::npos;
}

string_view recortar(string_view s) {
    constexpr string_view blancos = " \t";
    const auto desde = s.find_first_not_of(blancos);
    if (desde == string_view::npos) return {};
    return s.substr(desde, s.find_last_not_of(blancos) - desde + 1);
}

expected<string, Rechazo> linea(Lector& lector) {
    string crudo;
    for (int c = lector.octeto(); c != '\n'; c = lector.octeto()) {
        if (c < 0) return Fallo{Rechazo::MalFormada};
        if (crudo.size() >= kMaxLinea) return Fallo{Rechazo::Demasiado};
        crudo.push_back(static_cast<char>(c));
    }
    if (crudo.ends_with('\r')) crudo.pop_back();
    if (crudo.contains('\r')) return Fallo{Rechazo::MalFormada};
    return crudo;
}

expected<void, Rechazo> partir_inicial(string_view l, Peticion& p) {
    const auto uno = l.find(' ');
    const auto dos = uno == string_view::npos ? uno : l.find(' ', uno + 1);
    if (uno == string_view::npos || dos == string_view::npos ||
        l.find(' ', dos + 1) != string_view::npos || uno == 0 || dos == uno + 1 ||
        dos + 1 == l.size()) {
        return Fallo{Rechazo::MalFormada};
    }
    p.metodo = l.substr(0, uno);
    p.destino = l.substr(uno + 1, dos - uno - 1);
    p.version = l.substr(dos + 1);

    if (!metodo_conocido(p.metodo)) {
        return Fallo{Rechazo::NoImplementado};
    }
    if (p.version == "HTTP/1.1" || p.version == "HTTP/1.0") return {};
    if (p.version.starts_with("HTTP/")) return Fallo{Rechazo::VersionNoSoportada};
    return Fallo{Rechazo::MalFormada};
}

expected<void, Rechazo> leer_cabeceras(Lector& lector, Peticion& p) {
    for (size_t vistas = 0;; ++vistas) {
        const auto l = linea(lector);
        if (!l) return Fallo{l.error()};
        if (l->empty()) return {};
        if (vistas >= kMaxCabeceras) return Fallo{Rechazo::Demasiado};

        if (l->starts_with(' ') || l->starts_with('\t')) return Fallo{Rechazo::MalFormada};

        const auto corte = l->find(':');
        if (corte == string::npos || corte == 0) return Fallo{Rechazo::MalFormada};
        const string_view nombre{l->data(), corte};
        const string_view valor = string_view{*l}.substr(corte + 1);

        if (!ranges::all_of(nombre, [](char c) { return es_token(static_cast<unsigned char>(c)); })) {
            return Fallo{Rechazo::MalFormada};
        }
        if (ranges::any_of(valor, [](char ch) {
                const auto c = static_cast<unsigned char>(ch);
                return c == 0 || (c < 0x20 && c != '\t') || c == 0x7f;
            })) {
            return Fallo{Rechazo::MalFormada};
        }

        const string clave = minuscula(nombre);
        const string limpio{recortar(valor)};
        const auto previo = p.cabeceras.find(clave);
        if (previo == p.cabeceras.end()) {
            p.cabeceras.emplace(clave, limpio);
            continue;
        }
        if (clave == "host") return Fallo{Rechazo::MalFormada};
        if (clave == "content-length") {
            if (previo->second != limpio) return Fallo{Rechazo::MalFormada};
            continue;
        }
        previo->second += ", " + limpio;
    }
}

expected<string, Rechazo> exactos(Lector& lector, size_t cuantos) {
    string datos;
    datos.reserve(cuantos);
    for (size_t i = 0; i < cuantos; ++i) {
        const int c = lector.octeto();
        if (c < 0) return Fallo{Rechazo::MalFormada};
        datos.push_back(static_cast<char>(c));
    }
    return datos;
}

expected<string, Rechazo> por_trozos(Lector& lector, size_t max_cuerpo) {
    string cuerpo;
    while (true) {
        const auto cabecera = linea(lector);
        if (!cabecera) return Fallo{cabecera.error()};
        const string_view medida = recortar(string_view{*cabecera}.substr(
            0, min(cabecera->find(';'), cabecera->size())));

        size_t tamano = 0;
        const auto fin = medida.data() + medida.size();
        const auto leido = from_chars(medida.data(), fin, tamano, 16);
        if (leido.ec != errc{} || leido.ptr != fin) return Fallo{Rechazo::MalFormada};

        if (tamano > max_cuerpo - cuerpo.size()) return Fallo{Rechazo::CuerpoGrande};
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

expected<void, Rechazo> leer_cuerpo(Lector& lector, Peticion& p, size_t max_cuerpo) {
    const auto te = p.cabecera("transfer-encoding");
    const auto cl = p.cabecera("content-length");
    if (te) {
        if (cl) return Fallo{Rechazo::MalFormada};
        const auto coma = te->rfind(',');
        const auto ultima = recortar(coma == string_view::npos ? *te : te->substr(coma + 1));
        if (minuscula(ultima) != "chunked") return Fallo{Rechazo::MalFormada};
        auto cuerpo = por_trozos(lector, max_cuerpo);
        if (!cuerpo) return Fallo{cuerpo.error()};
        p.cuerpo = std::move(*cuerpo);
        return {};
    }
    if (!cl) return {};

    uint64_t largo = 0;
    const auto fin = cl->data() + cl->size();
    const auto leido = from_chars(cl->data(), fin, largo);
    if (leido.ec != errc{} || leido.ptr != fin) return Fallo{Rechazo::MalFormada};
    if (largo > max_cuerpo) return Fallo{Rechazo::CuerpoGrande};

    auto cuerpo = exactos(lector, static_cast<size_t>(largo));
    if (!cuerpo) return Fallo{cuerpo.error()};
    p.cuerpo = std::move(*cuerpo);
    return {};
}

}

optional<string_view> Peticion::cabecera(string_view nombre) const {
    const auto i = cabeceras.find(minuscula(nombre));
    if (i == cabeceras.end()) return nullopt;
    return i->second;
}

string_view Peticion::camino() const {
    string_view d{destino};
    d = d.substr(0, min(d.find('?'), d.size()));
    for (string_view esquema : {"http://", "https://"}) {
        if (!d.starts_with(esquema)) continue;
        d.remove_prefix(esquema.size());
        const auto barra = d.find('/');
        return barra == string_view::npos ? string_view{"/"} : d.substr(barra);
    }
    return d;
}

bool metodo_conocido(string_view metodo) {
    return ranges::find(kMetodos, metodo) != kMetodos.end();
}

expected<Peticion, Rechazo> leer(Lector& lector, size_t max_cuerpo) {
    Peticion p;
    const auto inicial = linea(lector);
    if (!inicial) return Fallo{inicial.error()};
    if (auto r = partir_inicial(*inicial, p); !r) return Fallo{r.error()};
    if (auto r = leer_cabeceras(lector, p); !r) return Fallo{r.error()};
    if (p.version == "HTTP/1.1" && !p.cabecera("host")) return Fallo{Rechazo::MalFormada};
    if (auto r = leer_cuerpo(lector, p, max_cuerpo); !r) return Fallo{r.error()};
    return p;
}

}
