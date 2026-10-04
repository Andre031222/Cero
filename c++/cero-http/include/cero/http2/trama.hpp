#pragma once

#include <cstdint>
#include <expected>
#include <string>
#include <string_view>
#include <vector>

// Tramas HTTP/2, según `spec/http2.md`.
//
// Casi todos los requisitos de este bloque son rechazos: qué **no** se admite y con qué código. La
// asimetría no es casual — un servidor HTTP/2 permisivo es un servidor que desacuerda con el proxy
// que tiene delante, y ahí empieza el contrabando.

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

// Códigos de error del RFC 9113 §7.
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

// Un fallo que se lleva la conexión entera por delante.
struct FalloConexion {
    Error codigo;
    std::string_view porque;
};

// Un fallo que solo anula un flujo. La distinción importa: tumbar la conexión por un flujo malo
// deja sin servicio a los demás, que es justo lo que HTTP/2 vino a evitar.
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
    // Los rechazos que dependen solo de la trama, sin mirar el estado de la conexión.
    // `H2-002` a `H2-013`.
    Resultado<void> comprobar() const;

    bool operator==(const Trama&) const = default;

    static Trama ping_ack(std::string_view carga);
    static Trama settings_ack();
    static Trama rst(std::uint32_t flujo, Error codigo);
    static Trama goaway(std::uint32_t ultimo, Error codigo);
    static Trama ventana(std::uint32_t flujo, std::uint32_t cuanto);
};

// De dónde salen los octetos de la conexión. Igual que en HTTP/1.1, el parser no sabe si detrás
// hay un socket o una cadena, y por eso se puede probar sin abrir un puerto.
class Fuente {
public:
    virtual ~Fuente() = default;
    // Devuelve menos de `cuantos` solo al acabarse.
    virtual std::size_t leer(char* destino, std::size_t cuantos) = 0;
};

// H2-014: una trama mayor que lo negociado no se admite. Se comprueba **antes** de leer la carga:
// si se leyera primero, anunciar 16 MB bastaría para que el servidor los reservara.
Resultado<Trama> leer_trama(Fuente& origen, std::uint32_t max);

// Los ajustes que el RFC 9113 §6.5.2 define.
//
// Hay **dos juegos por conexión** y confundirlos es un agujero, no un descuido: los nuestros dicen
// lo que admitimos recibir y los del cliente lo que él admite recibir. Dejar que los suyos pisen
// los nuestros deja al cliente subiendo nuestros propios topes por SETTINGS.
struct Ajustes {
    std::uint32_t max_flujos = 128;
    std::uint32_t ventana_inicial = 65'535;
    std::uint32_t max_trama = 16'384;
    std::uint32_t max_cabeceras = 16'384;

    // Los que hay que suponerle al cliente hasta que mande los suyos. No son los nuestros:
    // suponer aquí lo que nosotros anunciamos sería mandarle tramas que no aceptó.
    static Ajustes del_rfc();

    // H2-027: el servidor manda los suyos como primera trama.
    Trama trama() const;
    // Aplica los del cliente. Devuelve el delta de ventana inicial, que hay que sumar a los flujos
    // ya abiertos (§6.9.2).
    Resultado<std::int64_t> aplicar(std::string_view carga);
};

std::uint32_t entero32(std::string_view donde, std::size_t desde);

}  // namespace cero::http2
