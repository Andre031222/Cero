#pragma once

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <format>
#include <string>
#include <string_view>
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

template <class... Datos>
void aviso(std::format_string<Datos...> plantilla, Datos&&... datos) {
    const auto s = formato(plantilla, std::forward<Datos>(datos)...);
    std::fwrite(s.data(), 1, s.size(), stderr);
    std::fputc('\n', stderr);
}

inline std::string minuscula(std::string_view s) {
    std::string r{s};
    std::ranges::transform(r, r.begin(), [](unsigned char c) { return std::tolower(c); });
    return r;
}

}
