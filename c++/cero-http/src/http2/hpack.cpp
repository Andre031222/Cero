#include "cero/http2/hpack.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/formato.hpp"
#include "cero/http2/hpack_tablas.hpp"

using namespace std;

namespace cero::http2 {
namespace {

using Fallo = unexpected<FalloConexion>;

Fallo mal(string_view porque) { return Fallo{FalloConexion{Error::Compresion, porque}}; }

Fallo calma(string_view porque) { return Fallo{FalloConexion{Error::Calma, porque}}; }

unsigned char en(string_view s, size_t i) {
    return static_cast<unsigned char>(s[i]);
}

Resultado<size_t> leer_entero(string_view bloque, size_t& i, uint8_t bits) {
    const size_t mascara = (size_t{1} << bits) - 1;
    if (i >= bloque.size()) return mal("entero HPACK cortado");
    size_t valor = en(bloque, i) & mascara;
    ++i;
    if (valor < mascara) return valor;

    for (unsigned desplazamiento = 0;; desplazamiento += 7) {
        if (i >= bloque.size()) return mal("entero HPACK cortado");
        if (desplazamiento > 28) return mal("entero HPACK demasiado grande");
        const auto octeto = en(bloque, i);
        ++i;
        valor += static_cast<size_t>(octeto & 0x7f) << desplazamiento;
        if ((octeto & 0x80) == 0) return valor;
    }
}

optional<pair<size_t, unsigned>> casar(uint64_t acumulado, unsigned bits) {
    for (size_t simbolo = 0; simbolo <= kEos; ++simbolo) {
        const unsigned largo = kLargo[simbolo];
        if (largo > bits) continue;
        if (static_cast<uint32_t>(acumulado >> (bits - largo)) == kCodigo[simbolo]) {
            return pair{simbolo, largo};
        }
    }
    return nullopt;
}

Resultado<string> leer_cadena(string_view bloque, size_t& i) {
    if (i >= bloque.size()) return mal("cadena HPACK cortada");
    const bool huffman = (en(bloque, i) & 0x80) != 0;
    const auto largo = leer_entero(bloque, i, 7);
    if (!largo) return Fallo{largo.error()};
    if (i + *largo > bloque.size()) return mal("cadena HPACK cortada");
    const auto datos = bloque.substr(i, *largo);
    i += *largo;
    if (!huffman) return string{datos};
    return descomprimir(datos);
}

optional<size_t> indice_exacto(string_view nombre, string_view valor) {
    for (size_t i = 0; i < kEstatica.size(); ++i) {
        if (kEstatica[i].first == nombre && kEstatica[i].second == valor) return i + 1;
    }
    return nullopt;
}

optional<size_t> indice_de_nombre(string_view nombre) {
    for (size_t i = 0; i < kEstatica.size(); ++i) {
        if (kEstatica[i].first == nombre) return i + 1;
    }
    return nullopt;
}

void escribir_entero(string& salida, size_t valor, uint8_t bits, uint8_t bandera) {
    const size_t mascara = (size_t{1} << bits) - 1;
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

void escribir_cadena(string& salida, string_view texto) {
    const auto comprimido = comprimir(texto);
    if (comprimido.size() < texto.size()) {
        escribir_entero(salida, comprimido.size(), 7, 0x80);
        salida += comprimido;
    } else {
        escribir_entero(salida, texto.size(), 7, 0x00);
        salida += texto;
    }
}

}

void TablaDinamica::meter(string nombre, string valor) {
    const auto cuanto = coste(nombre, valor);
    while (ocupado_ + cuanto > tope_) {
        if (entradas_.empty()) return;
        ocupado_ -= coste(entradas_.back().first, entradas_.back().second);
        entradas_.pop_back();
    }
    ocupado_ += cuanto;
    entradas_.insert(entradas_.begin(), Cabecera{std::move(nombre), std::move(valor)});
}

Resultado<void> TablaDinamica::redimensionar(size_t nuevo) {
    if (nuevo > tope_maximo_) return mal("actualización de tabla mayor que la negociada");
    tope_ = nuevo;
    while (ocupado_ > tope_ && !entradas_.empty()) {
        ocupado_ -= coste(entradas_.back().first, entradas_.back().second);
        entradas_.pop_back();
    }
    return {};
}

const Cabecera* TablaDinamica::en(size_t indice) const {
    return indice < entradas_.size() ? &entradas_[indice] : nullptr;
}

Resultado<Cabecera> Decodificador::buscar(size_t indice) const {
    if (indice <= kEstatica.size()) {
        const auto& [n, v] = kEstatica[indice - 1];
        return Cabecera{string{n}, string{v}};
    }
    const auto* fila = tabla_.en(indice - kEstatica.size() - 1);
    if (fila == nullptr) return mal("índice de HPACK fuera de la tabla");
    return *fila;
}

Resultado<Cabecera> Decodificador::literal(string_view bloque, size_t& i, uint8_t bits) {
    const auto indice = leer_entero(bloque, i, bits);
    if (!indice) return Fallo{indice.error()};

    string nombre;
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

Resultado<Cabeceras> Decodificador::decodificar(string_view bloque) {
    Cabeceras salida;
    size_t tamano = 0;
    size_t i = 0;
    bool admite_actualizacion = true;

    while (i < bloque.size()) {
        const auto primero = en(bloque, i);
        Resultado<Cabecera> cual = Cabecera{};

        if ((primero & 0x80) != 0) {
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
            admite_actualizacion = false;
            cual = literal(bloque, i, 4);
        }

        if (!cual) return Fallo{cual.error()};
        tamano += cual->first.size() + cual->second.size() + kCosteFijo;
        if (tamano > max_lista_) {
            return calma("la lista de cabeceras se pasa del máximo al expandirse");
        }
        if (salida.size() == kMaxCampos) return calma("más campos de los que se admiten");
        salida.push_back(std::move(*cual));
    }
    return salida;
}

Resultado<string> descomprimir(string_view datos) {
    string salida;
    salida.reserve(datos.size() * 8 / 5);
    uint64_t acumulado = 0;
    unsigned bits = 0;

    for (char c : datos) {
        acumulado = (acumulado << 8) | static_cast<unsigned char>(c);
        bits += 8;
        while (bits >= 5) {
            const auto casado = casar(acumulado, bits);
            if (!casado) break;
            const auto [simbolo, largo] = *casado;
            if (simbolo == kEos) return mal("EOS dentro de una cadena Huffman");
            salida += static_cast<char>(simbolo);
            bits -= largo;
            acumulado &= (uint64_t{1} << bits) - 1;
        }
    }

    if (bits >= 8) return mal("relleno Huffman de ocho bits o más");
    if (bits > 0 && acumulado != (uint64_t{1} << bits) - 1) {
        return mal("relleno Huffman que no son unos");
    }
    return salida;
}

string comprimir(string_view datos) {
    string salida;
    uint64_t acumulado = 0;
    unsigned bits = 0;
    for (char c : datos) {
        const auto simbolo = static_cast<unsigned char>(c);
        const unsigned largo = kLargo[simbolo];
        acumulado = (acumulado << largo) | kCodigo[simbolo];
        bits += largo;
        while (bits >= 8) {
            salida += static_cast<char>((acumulado >> (bits - 8)) & 0xff);
            bits -= 8;
            acumulado &= (uint64_t{1} << bits) - 1;
        }
    }
    if (bits > 0) {
        const auto hueco = 8 - bits;
        salida += static_cast<char>(
            ((acumulado << hueco) | ((uint64_t{1} << hueco) - 1)) & 0xff);
    }
    return salida;
}

string codificar(const Cabeceras& cabeceras) {
    string salida;
    for (const auto& [nombre, valor] : cabeceras) {
        const auto minusculas = minuscula(nombre);
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

}
