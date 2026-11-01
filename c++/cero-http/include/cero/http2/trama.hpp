#pragma once

#include <cstdint>
#include <expected>
#include <string>
#include <string_view>
#include <vector>

namespace cero::http2 {

inline constexpr std::string_view kPreambulo = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

inline constexpr std::uint8_t kData = 0x0;
inline constexpr std::uint8_t kHeaders = 0x1;
inline constexpr std::uint8_t kPriority = 0x2;
inline constexpr std::uint8_t kRstStream = 0x3;
inline constexpr std::uint8_t kSettings = 0x4;
inline constexpr std::uint8_t kPushPromise = 0x5;
inline constexpr std::uint8_t kPing = 0x6;
inline constexpr std::uint8_t kGoaway = 0x7;
inline constexpr std::uint8_t kWindowUpdate = 0x8;
inline constexpr std::uint8_t kContinuation = 0x9;

inline constexpr std::uint8_t kFinFlujo = 0x1;
inline constexpr std::uint8_t kFinCabeceras = 0x4;
inline constexpr std::uint8_t kReconoce = 0x1;
inline constexpr std::uint8_t kRelleno = 0x8;
inline constexpr std::uint8_t kPrioridad = 0x20;

enum class Error : std::uint32_t {
    Ninguno = 0x0,
    Protocolo = 0x1,
    Interno = 0x2,
    ControlDeFlujo = 0x3,
    FlujoCerrado = 0x5,
    TamanoDeTrama = 0x6,
    Rechazado = 0x7,
    Anulado = 0x8,
    Compresion = 0x9,
    Calma = 0xb,
};

struct FalloConexion {
    Error codigo;
    std::string_view porque;
};

struct Cortado {
    std::uint32_t flujo;
    Error codigo;
    std::string_view porque;
};

template <class T>
using Resultado = std::expected<T, FalloConexion>;

struct Trama {
    std::uint8_t tipo = 0;
    std::uint8_t banderas = 0;
    std::uint32_t flujo = 0;
    std::string carga;

    bool fin_flujo() const { return (banderas & kFinFlujo) != 0; }
    bool fin_cabeceras() const { return (banderas & kFinCabeceras) != 0; }

    std::string octetos() const;
    Resultado<void> comprobar() const;

    bool operator==(const Trama&) const = default;

    static Trama ping_ack(std::string_view carga);
    static Trama settings_ack();
    static Trama rst(std::uint32_t flujo, Error codigo);
    static Trama goaway(std::uint32_t ultimo, Error codigo);
    static Trama ventana(std::uint32_t flujo, std::uint32_t cuanto);
};

class Fuente {
public:
    virtual ~Fuente() = default;
    virtual std::size_t leer(char* destino, std::size_t cuantos) = 0;
};

Resultado<Trama> leer_trama(Fuente& origen, std::uint32_t max);

struct Ajustes {
    std::uint32_t max_flujos = 128;
    std::uint32_t ventana_inicial = 65'535;
    std::uint32_t max_trama = 16'384;
    std::uint32_t max_cabeceras = 16'384;

    static Ajustes del_rfc();

    Trama trama() const;
    Resultado<std::int64_t> aplicar(std::string_view carga);
};

std::uint32_t entero32(std::string_view donde, std::size_t desde);

}
