#pragma once

#include <functional>
#include <map>
#include <string>
#include <string_view>
#include <vector>

namespace cero {

template <class T>
using PorNombre = std::map<std::string, T, std::less<>>;

using Textos = std::vector<std::string>;
using Vistas = std::vector<std::string_view>;

}
