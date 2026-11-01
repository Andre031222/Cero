#pragma once

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "cero/http2/hpack.hpp"
#include "cero/http2/trama.hpp"
#include "cero/peticion.hpp"
#include "cero/respuesta.hpp"
#include "cero/socket.hpp"

namespace cero::http2 {

class DesdeSocket final : public Fuente, public cero::Lector {
public:
    explicit DesdeSocket(int descriptor) : descriptor_(descriptor) {}

    int octeto() override;
    std::size_t leer(char* destino, std::size_t cuantos) override;
    bool asomar(std::string_view prefijo);
    bool esperar(std::chrono::milliseconds cuanto);
    int descriptor() const { return descriptor_; }

private:
    bool llenar();

    int descriptor_;
    std::array<char, 16'384> buf_{};
    std::size_t i_ = 0;
    std::size_t tengo_ = 0;
};

using Atender = std::function<cero::Respuesta(const cero::Peticion&)>;

inline constexpr std::string_view kAscenso =
    "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n\r\n";

bool pide_h2c(const cero::Peticion& p);
std::optional<std::string> base64url(std::string_view texto);

std::vector<Trama> tramas_de_cabeceras(std::uint32_t flujo, std::string_view bloque,
                                       std::size_t max, bool fin);

cero::Peticion armar(std::vector<Cabecera> cabeceras, std::string cuerpo);

void servir(DesdeSocket& entrada, Ajustes mios, const Atender& atender,
            std::optional<cero::Peticion> primera = std::nullopt, cero::Plazos plazos = {},
            cero::Limites limites = {});

}
