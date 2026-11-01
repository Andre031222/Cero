#include "cero/socket.hpp"

#include <sys/socket.h>
#include <sys/time.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <string_view>

namespace cero {
namespace {

#ifdef MSG_NOSIGNAL
constexpr int kSinSenal = MSG_NOSIGNAL;
#else
constexpr int kSinSenal = 0;
#endif

constexpr std::size_t kTrozo = 16 * 1024;
constexpr std::size_t kPieza = 4 * 1024;

void plazo(int descriptor, int opcion, std::chrono::milliseconds cuanto) {
    const auto segundos = std::chrono::duration_cast<std::chrono::seconds>(cuanto);
    timeval espera{};
    espera.tv_sec = static_cast<decltype(espera.tv_sec)>(segundos.count());
    espera.tv_usec = static_cast<decltype(espera.tv_usec)>(
        std::chrono::duration_cast<std::chrono::microseconds>(cuanto - segundos).count());
    ::setsockopt(descriptor, SOL_SOCKET, opcion, &espera, sizeof espera);
}

}

void sin_sigpipe([[maybe_unused]] int descriptor) {
#ifdef SO_NOSIGPIPE
    const int si = 1;
    ::setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &si, sizeof si);
#endif
}

void plazo_de_lectura(int descriptor, std::chrono::milliseconds cuanto) {
    plazo(descriptor, SO_RCVTIMEO, cuanto);
}

bool mandar_todo(int descriptor, std::string_view datos, const Plazos& plazos) {
    while (!datos.empty()) {
        auto trozo = datos.substr(0, kTrozo);
        datos.remove_prefix(trozo.size());
        const auto hasta = std::chrono::steady_clock::now() + plazos.escritura;
        while (!trozo.empty()) {
            const auto queda = std::chrono::duration_cast<std::chrono::milliseconds>(
                hasta - std::chrono::steady_clock::now());
            if (queda < std::chrono::milliseconds{1}) return false;
            plazo(descriptor, SO_SNDTIMEO, std::min(queda, plazos.sin_avance));
            const auto pieza = std::min(trozo.size(), kPieza);
            const auto escritos = ::send(descriptor, trozo.data(), pieza, kSinSenal);
            if (escritos < 0 && errno == EINTR) continue;
            if (escritos <= 0) return false;
            trozo.remove_prefix(static_cast<std::size_t>(escritos));
        }
    }
    return true;
}

}
