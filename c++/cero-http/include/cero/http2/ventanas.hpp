#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <map>
#include <mutex>
#include <optional>

namespace cero::http2 {

class Ventanas {
public:
    explicit Ventanas(std::int64_t inicial = 65'535) : inicial_(inicial) {}

    void abrir(std::uint32_t flujo);
    void soltar(std::uint32_t flujo);
    bool ampliar(std::uint32_t flujo, std::int64_t cuanto);
    bool reajustar(std::int64_t delta);
    std::optional<std::size_t> reservar(std::uint32_t flujo, std::size_t quiero,
                                        std::chrono::steady_clock::time_point hasta);
    void cerrar();
    std::optional<std::int64_t> ventana(std::uint32_t flujo) const;

private:
    mutable std::mutex candado_;
    std::condition_variable hay_;
    std::int64_t conexion_ = 65'535;
    std::int64_t inicial_;
    std::map<std::uint32_t, std::int64_t> flujos_;
    bool abierto_ = true;
};

}
