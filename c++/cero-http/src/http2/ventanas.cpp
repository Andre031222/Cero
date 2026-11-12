#include "cero/http2/ventanas.hpp"

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>

using namespace std;

namespace cero::http2 {
namespace {

constexpr int64_t kTope = 0x7fff'ffff;

}

void Ventanas::abrir(uint32_t flujo) {
    const lock_guard tomado{candado_};
    flujos_.insert_or_assign(flujo, inicial_);
}

void Ventanas::soltar(uint32_t flujo) {
    {
        const lock_guard tomado{candado_};
        flujos_.erase(flujo);
    }
    hay_.notify_all();
}

bool Ventanas::ampliar(uint32_t flujo, int64_t cuanto) {
    bool despierta = false;
    {
        const lock_guard tomado{candado_};
        auto* ventana = &conexion_;
        if (flujo != 0) {
            const auto i = flujos_.find(flujo);
            if (i == flujos_.end()) return true;
            ventana = &i->second;
        }
        if (*ventana + cuanto > kTope) return false;
        despierta = *ventana <= 0 && *ventana + cuanto > 0;
        *ventana += cuanto;
    }
    if (despierta) hay_.notify_all();
    return true;
}

bool Ventanas::reajustar(int64_t delta) {
    {
        const lock_guard tomado{candado_};
        if (ranges::any_of(flujos_, [&](const auto& f) { return f.second + delta > kTope; })) {
            return false;
        }
        inicial_ += delta;
        for (auto& f : flujos_) f.second += delta;
    }
    if (delta > 0) hay_.notify_all();
    return true;
}

optional<size_t> Ventanas::reservar(uint32_t flujo, size_t quiero,
                                    chrono::steady_clock::time_point hasta) {
    unique_lock tomado{candado_};
    while (abierto_) {
        const auto i = flujos_.find(flujo);
        if (i == flujos_.end()) return nullopt;
        const auto cabe = min({i->second, conexion_, static_cast<int64_t>(quiero)});
        if (cabe > 0) {
            i->second -= cabe;
            conexion_ -= cabe;
            return static_cast<size_t>(cabe);
        }
        if (hay_.wait_until(tomado, hasta) == cv_status::timeout) return nullopt;
    }
    return nullopt;
}

void Ventanas::cerrar() {
    {
        const lock_guard tomado{candado_};
        abierto_ = false;
    }
    hay_.notify_all();
}

optional<int64_t> Ventanas::ventana(uint32_t flujo) const {
    const lock_guard tomado{candado_};
    if (flujo == 0) return conexion_;
    const auto i = flujos_.find(flujo);
    if (i == flujos_.end()) return nullopt;
    return i->second;
}

}
