#pragma once

#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

#include "cero/http2/hpack.hpp"
#include "cero/http2/trama.hpp"

namespace cero::http2 {

struct Escribir {
    Trama trama;
};
struct Peticion {
    std::uint32_t flujo;
    std::vector<Cabecera> cabeceras;
    bool fin;
};
struct Cuerpo {
    std::uint32_t flujo;
    std::string datos;
    bool fin;
};
struct Anulado {
    std::uint32_t flujo;
};
struct Credito {
    std::uint32_t flujo;
    std::int64_t cuanto;
};
struct Reajustar {
    std::int64_t delta;
};

using Accion = std::variant<Escribir, Peticion, Cuerpo, Anulado, Credito, Reajustar>;

struct Digerido {
    std::vector<Accion> acciones;
    std::optional<Cortado> cortado;
};

struct Topes {
    std::size_t bloque_cabeceras = 64 * 1024;
    std::uint32_t anulados = 200;
    std::uint32_t control_sin_flujo = 1'000;
    std::uint32_t cortes = 1'000;
};

class Sesion {
public:
    explicit Sesion(Ajustes mios);

    Resultado<Digerido> recibir(const Trama& t);

    void respondido(std::uint32_t flujo);

    Resultado<void> ascender(std::string_view ajustes_del_cliente);

    std::uint32_t ultimo() const { return ultimo_; }

    std::size_t activos() const;
    std::optional<std::int64_t> ventana_de(std::uint32_t flujo) const;

    const Ajustes& mios() const { return mios_; }
    const Ajustes& par() const { return par_; }
    Topes topes;

private:
    enum class Estado { Abierto, MitadCerradoRemoto };

    struct Flujo {
        Estado estado = Estado::Abierto;
        std::int64_t ventana = 0;
        bool cabeceras_vistas = false;
        std::string_view malformado;
        Error por = Error::Protocolo;
        std::optional<std::uint64_t> declarado;
        std::uint64_t recibido = 0;
    };

    Resultado<void> control();
    Resultado<Digerido> settings(const Trama& t);
    Resultado<Digerido> ping(const Trama& t);
    Resultado<Digerido> window_update(const Trama& t);
    Resultado<Digerido> priority(const Trama& t);
    Resultado<Digerido> rst(const Trama& t);
    Resultado<Digerido> headers(const Trama& t);
    Resultado<Digerido> continuation(const Trama& t);
    Resultado<Digerido> data(const Trama& t);
    Resultado<Digerido> terminar_bloque(std::uint32_t flujo, std::string bloque, bool fin,
                                        bool trailers);
    Resultado<Digerido> cortar(std::uint32_t flujo, std::string_view porque,
                               Error por = Error::Protocolo);

    Ajustes mios_;
    Ajustes par_ = Ajustes::del_rfc();
    Decodificador decodificador_;
    std::map<std::uint32_t, Flujo> flujos_;
    std::set<std::uint32_t> atendidos_;
    std::uint32_t ultimo_ = 0;
    std::int64_t ventana_ = 0;
    struct Pendiente {
        std::uint32_t flujo;
        std::string bloque;
        bool fin;
    };
    std::optional<Pendiente> pendiente_;
    std::uint32_t anulados_ = 0;
    std::uint32_t control_sin_flujo_ = 0;
    std::uint32_t cortes_ = 0;
};

std::string cabeceras_de_respuesta(unsigned estado, const std::vector<Cabecera>& cabeceras);

std::string_view validar(const std::vector<Cabecera>& cabeceras);

}
