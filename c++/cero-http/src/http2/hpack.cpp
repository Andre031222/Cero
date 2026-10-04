#include "cero/http2/hpack.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cctype>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/http2/hpack_tablas.hpp"

namespace cero::http2 {
namespace {

using Fallo = std::unexpected<FalloConexion>;

Fallo mal(std::string_view porque) { return Fallo{FalloConexion{Error::Compresion, porque}}; }

unsigned char en(std::string_view s, std::size_t i) {
    return static_cast<unsigned char>(s[i]);
}

// Entero de longitud variable, §5.1. El prefijo lleva `bits` útiles; si están todos a uno, siguen
// septetos con el bit alto como continuación.
//
// El tope de cinco septetos no es decorativo: sin él, una tira de octetos `0xff` describe un
// entero arbitrariamente grande y el bucle no termina.
Resultado<std::size_t> leer_entero(std::string_view bloque, std::size_t& i, std::uint8_t bits) {
    const std::size_t mascara = (std::size_t{1} << bits) - 1;
    if (i >= bloque.size()) return mal("entero HPACK cortado");
    std::size_t valor = en(bloque, i) & mascara;
    ++i;
    if (valor < mascara) return valor;

    for (unsigned desplazamiento = 0;; desplazamiento += 7) {
        if (i >= bloque.size()) return mal("entero HPACK cortado");
        if (desplazamiento > 28) return mal("entero HPACK demasiado grande");
        const auto octeto = en(bloque, i);
        ++i;
        valor += static_cast<std::size_t>(octeto & 0x7f) << desplazamiento;
        if ((octeto & 0x80) == 0) return valor;
    }
}

// El símbolo más corto que casa con los bits de la cabeza, o nada si aún no hay bastantes.
std::optional<std::pair<std::size_t, unsigned>> casar(std::uint64_t acumulado, unsigned bits) {
    for (std::size_t simbolo = 0; simbolo <= kEos; ++simbolo) {
        const unsigned largo = kLargo[simbolo];
        if (largo > bits) continue;
        if (static_cast<std::uint32_t>(acumulado >> (bits - largo)) == kCodigo[simbolo]) {
            return std::pair{simbolo, largo};
        }
    }
    return std::nullopt;
}

// Cadena, §5.2: un bit de «viene en Huffman», la longitud en el mismo octeto y los datos.
Resultado<std::string> leer_cadena(std::string_view bloque, std::size_t& i) {
    if (i >= bloque.size()) return mal("cadena HPACK cortada");
    const bool huffman = (en(bloque, i) & 0x80) != 0;
    const auto largo = leer_entero(bloque, i, 7);
    if (!largo) return Fallo{largo.error()};
    if (i + *largo > bloque.size()) return mal("cadena HPACK cortada");
    const auto datos = bloque.substr(i, *largo);
    i += *largo;
    if (!huffman) return std::string{datos};
    return descomprimir(datos);
}

std::optional<std::size_t> indice_exacto(std::string_view nombre, std::string_view valor) {
    for (std::size_t i = 0; i < kEstatica.size(); ++i) {
        if (kEstatica[i].first == nombre && kEstatica[i].second == valor) return i + 1;
    }
    return std::nullopt;
}

std::optional<std::size_t> indice_de_nombre(std::string_view nombre) {
    for (std::size_t i = 0; i < kEstatica.size(); ++i) {
        if (kEstatica[i].first == nombre) return i + 1;
    }
    return std::nullopt;
}

void escribir_entero(std::string& salida, std::size_t valor, std::uint8_t bits,
                     std::uint8_t bandera) {
    const std::size_t mascara = (std::size_t{1} << bits) - 1;
    if (valor < mascara) {
        salida += static_cast<char>(bandera | valor);
        return;
    }
    salida += static_cast<char>(bandera | mascara);
    auto resto = valor - mascara;
    while (resto >= 0x80) {
        salida += static_cast<char>((resto & 0x7f) | 0x80);
        resto >>= 7;
    }
    salida += static_cast<char>(resto);
}

// Se escribe en Huffman solo si sale más corto. Comprimir cuando no comprime es gastar CPU por
// octetos de más, y el RFC lo deja a elección de quien codifica (§5.2).
void escribir_cadena(std::string& salida, std::string_view texto) {
    const auto comprimido = comprimir(texto);
    if (comprimido.size() < texto.size()) {
        escribir_entero(salida, comprimido.size(), 7, 0x80);
        salida += comprimido;
    } else {
        escribir_entero(salida, texto.size(), 7, 0x00);
        salida += texto;
    }
}

}  // namespace

void TablaDinamica::meter(std::string nombre, std::string valor) {
    const auto cuanto = coste(nombre, valor);
    while (ocupado_ + cuanto > tope_) {
        if (entradas_.empty()) return;
        ocupado_ -= coste(entradas_.back().first, entradas_.back().second);
        entradas_.pop_back();
    }
    ocupado_ += cuanto;
    entradas_.insert(entradas_.begin(), Cabecera{std::move(nombre), std::move(valor)});
}

Resultado<void> TablaDinamica::redimensionar(std::size_t nuevo) {
    if (nuevo > tope_maximo_) return mal("actualización de tabla mayor que la negociada");
    tope_ = nuevo;
    while (ocupado_ > tope_ && !entradas_.empty()) {
        ocupado_ -= coste(entradas_.back().first, entradas_.back().second);
        entradas_.pop_back();
    }
    return {};
}

const Cabecera* TablaDinamica::en(std::size_t indice) const {
    return indice < entradas_.size() ? &entradas_[indice] : nullptr;
}

// `H2-015`: un índice fuera de las dos tablas rompe la conexión.
Resultado<Cabecera> Decodificador::buscar(std::size_t indice) const {
    if (indice <= kEstatica.size()) {
        const auto& [n, v] = kEstatica[indice - 1];
        return Cabecera{std::string{n}, std::string{v}};
    }
    const auto* fila = tabla_.en(indice - kEstatica.size() - 1);
    if (fila == nullptr) return mal("índice de HPACK fuera de la tabla");
    return *fila;
}

Resultado<Cabecera> Decodificador::literal(std::string_view bloque, std::size_t& i,
                                           std::uint8_t bits) {
    const auto indice = leer_entero(bloque, i, bits);
    if (!indice) return Fallo{indice.error()};

    std::string nombre;
    if (*indice == 0) {
        auto leido = leer_cadena(bloque, i);
        if (!leido) return Fallo{leido.error()};
        nombre = std::move(*leido);
    } else {
        auto fila = buscar(*indice);
        if (!fila) return Fallo{fila.error()};
        nombre = std::move(fila->first);
    }
    auto valor = leer_cadena(bloque, i);
    if (!valor) return Fallo{valor.error()};
    return Cabecera{std::move(nombre), std::move(*valor)};
}

Resultado<std::vector<Cabecera>> Decodificador::decodificar(std::string_view bloque) {
    std::vector<Cabecera> salida;
    std::size_t tamano = 0;
    std::size_t i = 0;
    // Una actualización de tamaño solo vale al principio del bloque (§4.2).
    bool admite_actualizacion = true;

    while (i < bloque.size()) {
        const auto primero = en(bloque, i);
        Resultado<Cabecera> cual = Cabecera{};

        if ((primero & 0x80) != 0) {
            // Indexado entero: nombre y valor salen de la tabla.
            admite_actualizacion = false;
            const auto indice = leer_entero(bloque, i, 7);
            if (!indice) return Fallo{indice.error()};
            if (*indice == 0) return mal("índice 0 en una referencia indexada");
            cual = buscar(*indice);
        } else if ((primero & 0x40) != 0) {
            admite_actualizacion = false;
            cual = literal(bloque, i, 6);
            if (cual) tabla_.meter(cual->first, cual->second);
        } else if ((primero & 0x20) != 0) {
            if (!admite_actualizacion) {
                return mal("actualización de tabla fuera del principio del bloque");
            }
            const auto nuevo = leer_entero(bloque, i, 5);
            if (!nuevo) return Fallo{nuevo.error()};
            if (auto r = tabla_.redimensionar(*nuevo); !r) return Fallo{r.error()};
            continue;
        } else {
            // 0x10 es «nunca indexar»; para decodificar se trata igual que «sin indexar». La
            // diferencia solo obliga a quien reenvía, y aquí no se reenvía nada.
            admite_actualizacion = false;
            cual = literal(bloque, i, 4);
        }

        if (!cual) return Fallo{cual.error()};
        tamano += cual->first.size() + cual->second.size() + kCosteFijo;
        if (tamano > max_lista_) {
            return mal("la lista de cabeceras se pasa del máximo al expandirse");
        }
        salida.push_back(std::move(*cual));
    }
    return salida;
}

// Huffman, §5.2. Se recorre bit a bit contra la tabla: 257 símbolos de hasta 30 bits no justifican
// un árbol, y el bucle plano es el que se puede leer al lado del RFC.
Resultado<std::string> descomprimir(std::string_view datos) {
    std::string salida;
    salida.reserve(datos.size() * 8 / 5);
    std::uint64_t acumulado = 0;
    unsigned bits = 0;

    for (char c : datos) {
        acumulado = (acumulado << 8) | static_cast<unsigned char>(c);
        bits += 8;
        while (bits >= 5) {
            const auto casado = casar(acumulado, bits);
            if (!casado) break;
            const auto [simbolo, largo] = *casado;
            // H2-016: EOS dentro de la cadena, no como relleno.
            if (simbolo == kEos) return mal("EOS dentro de una cadena Huffman");
            salida += static_cast<char>(simbolo);
            bits -= largo;
            acumulado &= (std::uint64_t{1} << bits) - 1;
        }
    }

    // Lo que queda tiene que ser relleno: menos de ocho bits y todos a uno (§5.2). Un relleno más
    // largo o con un cero es un símbolo que se quedó a medias, y eso ya no es la misma cadena.
    if (bits >= 8) return mal("relleno Huffman de ocho bits o más");
    if (bits > 0 && acumulado != (std::uint64_t{1} << bits) - 1) {
        return mal("relleno Huffman que no son unos");
    }
    return salida;
}

std::string comprimir(std::string_view datos) {
    std::string salida;
    std::uint64_t acumulado = 0;
    unsigned bits = 0;
    for (char c : datos) {
        const auto simbolo = static_cast<unsigned char>(c);
        const unsigned largo = kLargo[simbolo];
        acumulado = (acumulado << largo) | kCodigo[simbolo];
        bits += largo;
        while (bits >= 8) {
            salida += static_cast<char>((acumulado >> (bits - 8)) & 0xff);
            bits -= 8;
            acumulado &= (std::uint64_t{1} << bits) - 1;
        }
    }
    if (bits > 0) {
        // El relleno son los bits altos de EOS, que son todos unos.
        const auto hueco = 8 - bits;
        salida += static_cast<char>(
            ((acumulado << hueco) | ((std::uint64_t{1} << hueco) - 1)) & 0xff);
    }
    return salida;
}

// Los nombres se pasan a minúsculas: en HTTP/2 una mayúscula en un nombre de campo es un mensaje
// malformado (§8.2.1), así que emitirla sería emitir algo que el cliente tiene que rechazar.
std::string codificar(const std::vector<Cabecera>& cabeceras) {
    std::string salida;
    for (const auto& [nombre, valor] : cabeceras) {
        std::string minusculas{nombre};
        std::ranges::transform(minusculas, minusculas.begin(),
                               [](unsigned char c) { return std::tolower(c); });
        if (const auto i = indice_exacto(minusculas, valor)) {
            escribir_entero(salida, *i, 7, 0x80);
        } else if (const auto j = indice_de_nombre(minusculas)) {
            escribir_entero(salida, *j, 4, 0x00);
            escribir_cadena(salida, valor);
        } else {
            salida += '\x00';
            escribir_cadena(salida, minusculas);
            escribir_cadena(salida, valor);
        }
    }
    return salida;
}

}  // namespace cero::http2
