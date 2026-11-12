#pragma once

#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

#include "cero/http2/conexion.hpp"
#include "cero/http2/hpack.hpp"
#include "cero/http2/trama.hpp"
#include "cero/respuesta.hpp"

namespace prueba {

inline std::vector<cero::http2::Cabecera> get() {
    return {{":method", "GET"}, {":scheme", "https"}, {":path", "/"}, {":authority", "a"}};
}

inline std::string ajuste(std::uint16_t id, std::uint32_t valor) {
    std::string s;
    s += static_cast<char>(id >> 8);
    s += static_cast<char>(id & 0xff);
    for (int desplaza = 24; desplaza >= 0; desplaza -= 8) {
        s += static_cast<char>((valor >> desplaza) & 0xff);
    }
    return s;
}

class Cliente {
public:
    explicit Cliente(int descriptor) : d_(descriptor), entrada_(descriptor) {
        timeval espera{};
        espera.tv_sec = 5;
        setsockopt(d_, SOL_SOCKET, SO_RCVTIMEO, &espera, sizeof espera);
        setsockopt(d_, SOL_SOCKET, SO_SNDTIMEO, &espera, sizeof espera);
    }
    ~Cliente() { close(d_); }
    Cliente(const Cliente&) = delete;
    Cliente& operator=(const Cliente&) = delete;

    bool mandar(std::string_view octetos) {
        while (!octetos.empty()) {
            const auto n = write(d_, octetos.data(), octetos.size());
            if (n <= 0) return false;
            octetos.remove_prefix(static_cast<std::size_t>(n));
        }
        return true;
    }
    bool mandar(const cero::http2::Trama& x) { return mandar(x.octetos()); }

    std::optional<cero::http2::Trama> trama() {
        auto r = cero::http2::leer_trama(entrada_, 1 << 24);
        if (!r) return std::nullopt;
        return std::move(*r);
    }

    std::optional<cero::http2::Trama> hasta(std::uint8_t tipo) {
        for (auto x = trama(); x; x = trama()) {
            if (x->tipo == tipo) return x;
        }
        return std::nullopt;
    }

    std::string linea() {
        std::string s;
        for (int c = entrada_.octeto(); c >= 0 && c != '\n'; c = entrada_.octeto()) {
            s += static_cast<char>(c);
        }
        return s;
    }

    void abrir_h2(std::string ajustes = {}) {
        mandar(cero::http2::kPreambulo);
        mandar(cero::http2::Trama{cero::http2::kSettings, 0, 0, std::move(ajustes)});
    }

    int descriptor() const { return d_; }

private:
    int d_;
    cero::http2::DesdeSocket entrada_;
};

inline std::string estado(const cero::http2::Trama& cabeceras) {
    cero::http2::Decodificador d{4096, 1 << 20};
    const auto c = d.decodificar(cabeceras.carga);
    if (!c || c->empty()) return {};
    return c->front().second;
}

struct Par {
    int cliente;
    std::thread servidor;
};

inline Par conductor(cero::http2::Atender atender, cero::http2::Ajustes ajustes = {}) {
    int fd[2];
    socketpair(AF_UNIX, SOCK_STREAM, 0, fd);
    std::thread hilo{[d = fd[0], atender = std::move(atender), ajustes] {
        cero::http2::DesdeSocket entrada{d};
        cero::http2::servir(entrada, ajustes, atender);
        close(d);
    }};
    return {fd[1], std::move(hilo)};
}

}
