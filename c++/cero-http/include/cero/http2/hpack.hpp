#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/http2/trama.hpp"

// HPACK (RFC 7541): la compresión de cabeceras de HTTP/2.
//
// Tres piezas encadenadas: los enteros de longitud variable (§5.1), las cadenas —literales o en
// Huffman— (§5.2) y la tabla de índices, estática más dinámica (§2.3).
//
// Lo que hace a HPACK distinto de un descompresor cualquiera es que **el estado es compartido y
// ordenado**: la tabla dinámica del decodificador tiene que quedar exactamente igual que la del
// codificador del otro lado después de cada bloque. Por eso un bloque que no se pueda decodificar
// rompe la conexión entera y no solo el flujo —`H2-015`, `H2-016`—: seguir sería interpretar las
// cabeceras siguientes contra una tabla desalineada, y eso no da un error, da cabeceras
// equivocadas. Y por eso los trailers se decodifican aunque se tiren.

namespace cero::http2 {

using Cabecera = std::pair<std::string, std::string>;

// El coste de una entrada, §4.1: los dos textos más 32 octetos de estructura. Es una convención
// del RFC y no una medida — lo que importa es que los dos extremos cuenten igual.
inline constexpr std::size_t kCosteFijo = 32;

// La tabla dinámica: las últimas cabeceras vistas, en orden de llegada, con un tope en octetos.
//
// El índice 1 del protocolo es la entrada más **reciente**, no la más antigua.
class TablaDinamica {
public:
    explicit TablaDinamica(std::size_t tope) : tope_(tope), tope_maximo_(tope) {}

    std::size_t tamano() const { return entradas_.size(); }
    std::size_t ocupado() const { return ocupado_; }

    // Mete una entrada y desaloja por el otro extremo hasta que quepa. Una que no cabe ni en la
    // tabla vacía **vacía la tabla y no se guarda** (§4.4). No es un error: es lo que deja las dos
    // tablas iguales.
    void meter(std::string nombre, std::string valor);
    // Una actualización de tamaño dinámico (§6.3). Reducir obliga a desalojar en el acto.
    Resultado<void> redimensionar(std::size_t nuevo);
    const Cabecera* en(std::size_t indice) const;

private:
    static std::size_t coste(std::string_view nombre, std::string_view valor) {
        return nombre.size() + valor.size() + kCosteFijo;
    }

    std::vector<Cabecera> entradas_;
    std::size_t ocupado_ = 0;
    std::size_t tope_;
    // Lo que la otra punta puede pedir con una actualización. `SETTINGS_HEADER_TABLE_SIZE` lo fija;
    // una actualización que lo supere es un error de compresión (§6.3).
    std::size_t tope_maximo_;
};

// Un decodificador vive tanto como la conexión: su tabla es el estado compartido con el cliente.
class Decodificador {
public:
    Decodificador(std::size_t tope_tabla, std::size_t max_lista)
        : tabla_(tope_tabla), max_lista_(max_lista) {}

    // Devuelve las cabeceras en el orden en que venían: el orden de los valores repetidos de un
    // mismo campo es significativo (§3.2.2 del 9113).
    Resultado<std::vector<Cabecera>> decodificar(std::string_view bloque);

    TablaDinamica& tabla() { return tabla_; }
    void max_lista(std::size_t cuanto) { max_lista_ = cuanto; }

private:
    Resultado<Cabecera> buscar(std::size_t indice) const;
    Resultado<Cabecera> literal(std::string_view bloque, std::size_t& i, std::uint8_t bits);

    TablaDinamica tabla_;
    // `H2-039`: el tope de lo que **sale**, no de lo que entra. Tres kilobytes comprimidos pueden
    // ser trescientos al expandirse, y limitar solo el bloque no lo ve venir.
    std::size_t max_lista_;
};

// El servidor solo codifica sus propias respuestas, y ahí la tabla dinámica aporta poco: lo que se
// repite entre respuestas —`content-type`, `server`— ya está en la estática. Se codifica sin
// indexar, que deja la tabla del cliente quieta y hace la salida reproducible.
std::string codificar(const std::vector<Cabecera>& cabeceras);

Resultado<std::string> descomprimir(std::string_view datos);
std::string comprimir(std::string_view datos);

}  // namespace cero::http2
