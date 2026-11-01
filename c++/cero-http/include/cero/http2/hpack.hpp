#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/http2/trama.hpp"

namespace cero::http2 {

using Cabecera = std::pair<std::string, std::string>;

inline constexpr std::size_t kCosteFijo = 32;

class TablaDinamica {
public:
    explicit TablaDinamica(std::size_t tope) : tope_(tope), tope_maximo_(tope) {}

    std::size_t tamano() const { return entradas_.size(); }
    std::size_t ocupado() const { return ocupado_; }

    void meter(std::string nombre, std::string valor);
    Resultado<void> redimensionar(std::size_t nuevo);
    const Cabecera* en(std::size_t indice) const;

private:
    static std::size_t coste(std::string_view nombre, std::string_view valor) {
        return nombre.size() + valor.size() + kCosteFijo;
    }

    std::vector<Cabecera> entradas_;
    std::size_t ocupado_ = 0;
    std::size_t tope_;
    std::size_t tope_maximo_;
};

class Decodificador {
public:
    Decodificador(std::size_t tope_tabla, std::size_t max_lista)
        : tabla_(tope_tabla), max_lista_(max_lista) {}

    Resultado<std::vector<Cabecera>> decodificar(std::string_view bloque);

    TablaDinamica& tabla() { return tabla_; }
    void max_lista(std::size_t cuanto) { max_lista_ = cuanto; }

private:
    Resultado<Cabecera> buscar(std::size_t indice) const;
    Resultado<Cabecera> literal(std::string_view bloque, std::size_t& i, std::uint8_t bits);

    TablaDinamica tabla_;
    std::size_t max_lista_;
};

std::string codificar(const std::vector<Cabecera>& cabeceras);

Resultado<std::string> descomprimir(std::string_view datos);
std::string comprimir(std::string_view datos);

}
