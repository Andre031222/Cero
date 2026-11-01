#include "cero/http2/ventanas.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>

namespace cero::http2 {
namespace {

constexpr std::int64_t kTope = 0x7fff'ffff;

}

void Ventanas::abrir(std::uint32_t flujo) {
    const std::lock_guard tomado{candado_};
    flujos_.insert_or_assign(flujo, inicial_);
}

void Ventanas::soltar(std::uint32_t flujo) {
    {
        const std::lock_guard tomado{candado_};
        flujos_.erase(flujo);
    }
    hay_.notify_all();
}

bool Ventanas::ampliar(std::uint32_t flujo, std::int64_t cuanto) {
    {
        const std::lock_guard tomado{candado_};
        auto* ventana = &conexion_;
        if (flujo != 0) {
            const auto i = flujos_.find(flujo);
            if (i == flujos_.end()) return true;
            ventana = &i->second;
        }
        if (*ventana + cuanto > kTope) return false;
        *ventana += cuanto;
    }
    hay_.notify_all();
    return true;
}

bool Ventanas::reajustar(std::int64_t delta) {
    {
        const std::lock_guard tomado{candado_};
        if (std::ranges::any_of(flujos_, [&](const auto& f) { return f.second + delta > kTope; })) {
            return false;
        }
        inicial_ += delta;
        for (auto& f : flujos_) f.second += delta;
    }
    hay_.notify_all();
    return true;
}

std::optional<std::size_t> Ventanas::reservar(std::uint32_t flujo, std::size_t quiero) {
    std::unique_lock tomado{candado_};
    while (abierto_) {
        const auto i = flujos_.find(flujo);
        if (i == flujos_.end()) return std::nullopt;
        const auto cabe = std::min({i->second, conexion_, static_cast<std::int64_t>(quiero)});
        if (cabe > 0) {
            i->second -= cabe;
            conexion_ -= cabe;
            return static_cast<std::size_t>(cabe);
        }
        hay_.wait(tomado);
    }
    return std::nullopt;
}

void Ventanas::cerrar() {
    {
        const std::lock_guard tomado{candado_};
        abierto_ = false;
    }
    hay_.notify_all();
}

std::optional<std::int64_t> Ventanas::ventana(std::uint32_t flujo) const {
    const std::lock_guard tomado{candado_};
    if (flujo == 0) return conexion_;
    const auto i = flujos_.find(flujo);
    if (i == flujos_.end()) return std::nullopt;
    return i->second;
}

}
