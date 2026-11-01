#pragma once

#include <cstdio>
#include <format>
#include <string>
#include <utility>

namespace cero {

template <class... Datos>
std::string formato(std::format_string<Datos...> plantilla, Datos&&... datos) {
    return std::format(plantilla, std::forward<Datos>(datos)...);
}

template <class... Datos>
void linea(std::format_string<Datos...> plantilla, Datos&&... datos) {
    const auto s = formato(plantilla, std::forward<Datos>(datos)...);
    std::fwrite(s.data(), 1, s.size(), stdout);
    std::fputc('\n', stdout);
}

}
