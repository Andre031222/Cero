#pragma once

#include <chrono>
#include <string_view>

namespace cero {

inline constexpr std::chrono::milliseconds kPlazoDeEscritura{30'000};

void sin_sigpipe(int descriptor);
void plazo_de_escritura(int descriptor, std::chrono::milliseconds plazo);
bool mandar_todo(int descriptor, std::string_view datos);

}
