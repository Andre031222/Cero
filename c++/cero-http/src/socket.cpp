#include "cero/socket.hpp"

#include <sys/socket.h>
#include <sys/time.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <string_view>

namespace cero {

int enlazar(int descriptor, const sockaddr* direccion, socklen_t largo) {
    return bind(descriptor, direccion, largo);
}

}

using namespace std;

namespace cero {
namespace {

#ifdef MSG_NOSIGNAL
constexpr int kSinSenal = MSG_NOSIGNAL;
#else
constexpr int kSinSenal = 0;
#endif

constexpr size_t kTrozo = 16 * 1024;
constexpr size_t kPieza = 4 * 1024;

void plazo(int descriptor, int opcion, chrono::milliseconds cuanto) {
    const auto segundos = chrono::duration_cast<chrono::seconds>(cuanto);
    timeval espera{};
    espera.tv_sec = static_cast<decltype(espera.tv_sec)>(segundos.count());
    espera.tv_usec = static_cast<decltype(espera.tv_usec)>(
        chrono::duration_cast<chrono::microseconds>(cuanto - segundos).count());
    setsockopt(descriptor, SOL_SOCKET, opcion, &espera, sizeof espera);
}

}

void sin_sigpipe([[maybe_unused]] int descriptor) {
#ifdef SO_NOSIGPIPE
    const int si = 1;
    setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &si, sizeof si);
#endif
}

void plazo_de_lectura(int descriptor, chrono::milliseconds cuanto) {
    plazo(descriptor, SO_RCVTIMEO, cuanto);
}

bool mandar_todo(int descriptor, string_view datos, const Plazos& plazos) {
    while (!datos.empty()) {
        auto trozo = datos.substr(0, kTrozo);
        datos.remove_prefix(trozo.size());
        const auto hasta = chrono::steady_clock::now() + plazos.escritura;
        while (!trozo.empty()) {
            const auto queda = chrono::duration_cast<chrono::milliseconds>(
                hasta - chrono::steady_clock::now());
            if (queda < chrono::milliseconds{1}) return false;
            plazo(descriptor, SO_SNDTIMEO, min(queda, plazos.sin_avance));
            const auto pieza = min(trozo.size(), kPieza);
            const auto escritos = send(descriptor, trozo.data(), pieza, kSinSenal);
            if (escritos < 0 && errno == EINTR) continue;
            if (escritos <= 0) return false;
            trozo.remove_prefix(static_cast<size_t>(escritos));
        }
    }
    return true;
}

}
