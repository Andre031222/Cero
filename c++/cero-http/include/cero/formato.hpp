#pragma once

#include <cstdio>
#include <format>
#include <string>
#include <utility>

namespace cero {

// `std::print` pide GCC 14; el estándar solo exige `<format>`, que está desde GCC 13. Dos líneas
// propias en vez de subir el compilador mínimo del proyecto. Y de paso los sitios que escriben
// quedan en `formato(...)` y `linea(...)`, sin el `std::` repetido por medio.
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

}  // namespace cero
