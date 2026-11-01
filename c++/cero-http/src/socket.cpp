#include "cero/socket.hpp"

#include <sys/socket.h>
#include <sys/time.h>

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

}

void sin_sigpipe([[maybe_unused]] int descriptor) {
#ifdef SO_NOSIGPIPE
    const int si = 1;
    ::setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &si, sizeof si);
#endif
}

void plazo_de_escritura(int descriptor, std::chrono::milliseconds plazo) {
    const auto segundos = std::chrono::duration_cast<std::chrono::seconds>(plazo);
    timeval espera{};
    espera.tv_sec = static_cast<decltype(espera.tv_sec)>(segundos.count());
    espera.tv_usec = static_cast<decltype(espera.tv_usec)>(
        std::chrono::duration_cast<std::chrono::microseconds>(plazo - segundos).count());
    ::setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &espera, sizeof espera);
}

bool mandar_todo(int descriptor, std::string_view datos) {
    while (!datos.empty()) {
        const auto escritos = ::send(descriptor, datos.data(), datos.size(), kSinSenal);
        if (escritos < 0 && errno == EINTR) continue;
        if (escritos <= 0) return false;
        datos.remove_prefix(static_cast<std::size_t>(escritos));
    }
    return true;
}

}
