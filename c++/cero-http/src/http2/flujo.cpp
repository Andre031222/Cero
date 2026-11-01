#include "cero/http2/flujo.hpp"

#include <algorithm>
#include <charconv>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/formato.hpp"

namespace cero::http2 {
namespace {

using Fallo = std::unexpected<FalloConexion>;

Fallo conexion(Error codigo, std::string_view porque) { return Fallo{FalloConexion{codigo, porque}}; }

constexpr std::int64_t kVentanaDeConexion = 65'535;

constexpr std::string_view kPseudo[]{":method", ":scheme", ":path", ":authority", ":status"};
constexpr std::string_view kDeConexion[]{"connection", "keep-alive", "proxy-connection",
                                         "transfer-encoding", "upgrade"};

bool es_de_conexion(std::string_view nombre) {
    return std::ranges::find(kDeConexion, nombre) != std::end(kDeConexion);
}

bool depende_de_si_mismo(std::string_view carga, std::uint32_t flujo) {
    return carga.size() >= 4 && (entero32(carga, 0) & 0x7fff'ffff) == flujo;
}

std::string_view prioridad_de(const Trama& t) {
    if ((t.banderas & kPrioridad) == 0) return {};
    const std::size_t desde = (t.banderas & kRelleno) != 0 ? 1 : 0;
    if (desde + 4 > t.carga.size()) return {};
    return std::string_view{t.carga}.substr(desde, 4);
}

Resultado<std::string> recortar(const Trama& t) {
    std::size_t desde = 0;
    std::size_t hasta = t.carga.size();
    const bool con_prioridad = t.tipo == kHeaders && (t.banderas & kPrioridad) != 0;
    if (con_prioridad) {
        if (hasta < 5) {
            return conexion(Error::TamanoDeTrama, "HEADERS con prioridad y sin los cinco octetos");
        }
        desde += 5;
    }
    if ((t.banderas & kRelleno) != 0) {
        if (t.carga.empty()) return conexion(Error::Protocolo, "relleno declarado y carga vacía");
        const auto relleno = static_cast<std::size_t>(static_cast<unsigned char>(t.carga[0]));
        desde = con_prioridad ? desde + 1 : 1;
        if (relleno + desde > hasta) return conexion(Error::Protocolo, "relleno mayor que la carga");
        hasta -= relleno;
    }
    return t.carga.substr(desde, hasta - desde);
}

std::expected<std::optional<std::uint64_t>, std::string_view> declarado(
    const std::vector<Cabecera>& cabeceras) {
    for (const auto& [nombre, valor] : cabeceras) {
        if (nombre != "content-length") continue;
        std::uint64_t cuanto = 0;
        const auto fin = valor.data() + valor.size();
        const auto leido = std::from_chars(valor.data(), fin, cuanto);
        if (leido.ec != std::errc{} || leido.ptr != fin) {
            return std::unexpected{std::string_view{"content-length que no es un número"}};
        }
        return cuanto;
    }
    return std::nullopt;
}

std::string_view recortar_blancos(std::string_view s) {
    const auto desde = s.find_first_not_of(" \t");
    if (desde == std::string_view::npos) return {};
    return s.substr(desde, s.find_last_not_of(" \t") - desde + 1);
}

}

std::string_view validar(const std::vector<Cabecera>& cabeceras) {
    bool metodo = false;
    bool esquema = false;
    bool camino = false;
    bool ya_hubo_normal = false;
    std::vector<std::string_view> vistos;

    for (const auto& [nombre, valor] : cabeceras) {
        if (std::ranges::any_of(nombre, [](unsigned char c) { return std::isupper(c) != 0; })) {
            return "nombre de campo con mayúsculas";
        }
        if (nombre.empty()) return "nombre de campo vacío";

        if (nombre.starts_with(':')) {
            if (ya_hubo_normal) return "pseudo-campo después de un campo normal";
            if (std::ranges::find(kPseudo, nombre) == std::end(kPseudo)) {
                return "pseudo-campo desconocido";
            }
            if (nombre == ":status") return ":status en una petición";
            if (std::ranges::find(vistos, nombre) != vistos.end()) return "pseudo-campo repetido";
            vistos.push_back(nombre);

            if (nombre == ":method") metodo = true;
            if (nombre == ":scheme") esquema = true;
            if (nombre == ":path") {
                if (valor.empty()) return ":path vacío";
                camino = true;
            }
        } else {
            ya_hubo_normal = true;
            if (es_de_conexion(nombre)) return "cabecera específica de conexión";
            if (nombre == "te" && recortar_blancos(valor) != "trailers") {
                return "TE con un valor distinto de trailers";
            }
        }
    }
    if (!metodo || !esquema || !camino) return "falta :method, :scheme o :path";
    return {};
}

std::string cabeceras_de_respuesta(unsigned estado, const std::vector<Cabecera>& cabeceras) {
    std::vector<Cabecera> salida{{":status", formato("{}", estado)}};
    for (const auto& [nombre, valor] : cabeceras) {
        std::string minusculas{nombre};
        std::ranges::transform(minusculas, minusculas.begin(),
                               [](unsigned char c) { return std::tolower(c); });
        if (es_de_conexion(minusculas)) continue;
        salida.emplace_back(std::move(minusculas), valor);
    }
    return codificar(salida);
}

Sesion::Sesion(Ajustes mios)
    : mios_(mios),
      decodificador_(4096, mios.max_cabeceras),
      ventana_(kVentanaDeConexion) {}

bool Sesion::es_preambulo(std::string_view octetos) {
    return octetos.size() >= kPreambulo.size() && octetos.substr(0, kPreambulo.size()) == kPreambulo;
}

std::optional<std::int64_t> Sesion::ventana_de(std::uint32_t flujo) const {
    const auto i = flujos_.find(flujo);
    if (i == flujos_.end()) return std::nullopt;
    return i->second.ventana;
}

void Sesion::respondido(std::uint32_t flujo) {
    const auto i = flujos_.find(flujo);
    if (i != flujos_.end() && i->second.estado == Estado::MitadCerradoRemoto) flujos_.erase(i);
}

Resultado<void> Sesion::ascender(std::string_view ajustes_del_cliente) {
    if (ajustes_del_cliente.size() % 6 != 0) {
        return conexion(Error::TamanoDeTrama, "HTTP2-Settings no múltiplo de seis");
    }
    if (const auto r = par_.aplicar(ajustes_del_cliente); !r) return Fallo{r.error()};
    Flujo uno;
    uno.estado = Estado::MitadCerradoRemoto;
    uno.cabeceras_vistas = true;
    uno.ventana = mios_.ventana_inicial;
    flujos_.emplace(1, uno);
    ultimo_ = 1;
    return {};
}

Resultado<Digerido> Sesion::recibir(const Trama& t) {
    if (auto r = t.comprobar(); !r) return Fallo{r.error()};

    if (pendiente_ && (t.tipo != kContinuation || t.flujo != pendiente_->flujo)) {
        return conexion(Error::Protocolo, "trama por medio de un bloque de cabeceras");
    }

    switch (t.tipo) {
        case kSettings: return settings(t);
        case kPing: return ping(t);
        case kWindowUpdate: return window_update(t);
        case kRstStream: return rst(t);
        case kHeaders: return headers(t);
        case kContinuation: return continuation(t);
        case kData: return data(t);
        case kPriority: return priority(t);
        case kGoaway: return Digerido{};
        default: return Digerido{};
    }
}

Resultado<void> Sesion::control() {
    ++control_sin_flujo_;
    if (control_sin_flujo_ > topes.control_sin_flujo) {
        return conexion(Error::Calma, "demasiadas tramas de control sin abrir un flujo");
    }
    return {};
}

Resultado<Digerido> Sesion::settings(const Trama& t) {
    if ((t.banderas & kReconoce) != 0) return Digerido{};
    if (auto r = control(); !r) return Fallo{r.error()};

    const auto delta = par_.aplicar(t.carga);
    if (!delta) return Fallo{delta.error()};

    Digerido d;
    d.acciones.emplace_back(Escribir{Trama::settings_ack()});
    if (*delta != 0) d.acciones.emplace_back(Reajustar{*delta});
    return d;
}

Resultado<Digerido> Sesion::ping(const Trama& t) {
    if ((t.banderas & kReconoce) != 0) return Digerido{};
    if (auto r = control(); !r) return Fallo{r.error()};
    return Digerido{{Escribir{Trama::ping_ack(t.carga)}}, std::nullopt};
}

Resultado<Digerido> Sesion::window_update(const Trama& t) {
    const auto cuanto = static_cast<std::int64_t>(entero32(t.carga, 0) & 0x7fff'ffff);
    if (t.flujo == 0) {
        if (auto r = control(); !r) return Fallo{r.error()};
        return Digerido{{Credito{0, cuanto}}, std::nullopt};
    }
    if (t.flujo > ultimo_) return conexion(Error::Protocolo, "WINDOW_UPDATE sobre un flujo ocioso");
    if (cuanto == 0) {
        return Digerido{{Escribir{Trama::rst(t.flujo, Error::Protocolo)}},
                        Cortado{t.flujo, Error::Protocolo, "incremento cero"}};
    }
    return Digerido{{Credito{t.flujo, cuanto}}, std::nullopt};
}

Resultado<Digerido> Sesion::priority(const Trama& t) {
    if (t.flujo == 0) return conexion(Error::Protocolo, "PRIORITY sobre la conexión");
    if (auto r = control(); !r) return Fallo{r.error()};
    if (depende_de_si_mismo(t.carga, t.flujo)) {
        return Digerido{{Escribir{Trama::rst(t.flujo, Error::Protocolo)}},
                        Cortado{t.flujo, Error::Protocolo, "depende de sí mismo"}};
    }
    return Digerido{};
}

Resultado<Digerido> Sesion::rst(const Trama& t) {
    if (t.flujo > ultimo_) {
        return conexion(Error::Protocolo, "RST_STREAM de un flujo que nunca se abrió");
    }
    flujos_.erase(t.flujo);
    ++anulados_;
    if (anulados_ > topes.anulados) {
        return conexion(Error::Calma, "demasiados flujos abiertos y anulados");
    }
    return Digerido{{Anulado{t.flujo}}, std::nullopt};
}

Resultado<Digerido> Sesion::headers(const Trama& t) {
    bool trailers = false;
    const auto existente = flujos_.find(t.flujo);
    if (existente != flujos_.end()) {
        auto& f = existente->second;
        if (f.estado == Estado::MitadCerradoRemoto) {
            f.malformado = "cabeceras después del fin de flujo";
            f.por = Error::FlujoCerrado;
        } else if (f.cabeceras_vistas) {
            if (!t.fin_flujo()) {
                return conexion(Error::Protocolo, "segundo bloque de cabeceras sin fin de flujo");
            }
            trailers = true;
        }
    } else {
        if (t.flujo <= ultimo_) {
            return conexion(Error::Protocolo, "identificador de flujo que no avanza");
        }
        Flujo nuevo;
        nuevo.ventana = mios_.ventana_inicial;
        if (activos() >= mios_.max_flujos) {
            nuevo.malformado = "tope de flujos";
            nuevo.por = Error::Rechazado;
        }
        ultimo_ = t.flujo;
        control_sin_flujo_ = 0;

        const auto prioridad = prioridad_de(t);
        if (nuevo.malformado.empty() && !prioridad.empty() &&
            depende_de_si_mismo(prioridad, t.flujo)) {
            nuevo.malformado = "el flujo depende de sí mismo";
        }
        flujos_.emplace(t.flujo, nuevo);
    }

    auto bloque = recortar(t);
    if (!bloque) return Fallo{bloque.error()};
    if (t.fin_cabeceras()) return terminar_bloque(t.flujo, std::move(*bloque), t.fin_flujo(), trailers);
    pendiente_ = Pendiente{t.flujo, std::move(*bloque), t.fin_flujo()};
    return Digerido{};
}

Resultado<Digerido> Sesion::continuation(const Trama& t) {
    if (!pendiente_) return conexion(Error::Protocolo, "CONTINUATION sin HEADERS delante");
    auto [flujo, bloque, fin] = std::move(*pendiente_);
    pendiente_.reset();

    bloque += t.carga;
    if (bloque.size() > topes.bloque_cabeceras) {
        return conexion(Error::Calma, "bloque de cabeceras sin fin");
    }
    const auto i = flujos_.find(flujo);
    const bool trailers = i != flujos_.end() && i->second.cabeceras_vistas;
    if (t.fin_cabeceras()) return terminar_bloque(flujo, std::move(bloque), fin, trailers);
    pendiente_ = Pendiente{flujo, std::move(bloque), fin};
    return Digerido{};
}

Digerido Sesion::cortar(std::uint32_t flujo, std::string_view porque, Error por) {
    flujos_.erase(flujo);
    return {{Escribir{Trama::rst(flujo, por)}}, Cortado{flujo, por, porque}};
}

Resultado<Digerido> Sesion::terminar_bloque(std::uint32_t flujo, std::string bloque, bool fin,
                                            bool trailers) {
    auto cabeceras = decodificador_.decodificar(bloque);
    if (!cabeceras) return Fallo{cabeceras.error()};

    const auto i = flujos_.find(flujo);
    if (i == flujos_.end()) return Digerido{};
    auto& f = i->second;
    if (!f.malformado.empty()) return cortar(flujo, f.malformado, f.por);
    f.cabeceras_vistas = true;
    if (fin) f.estado = Estado::MitadCerradoRemoto;

    if (trailers) {
        if (f.declarado && f.recibido != *f.declarado) {
            return cortar(flujo, "el cuerpo no mide lo que dijo content-length");
        }
        return Digerido{{Cuerpo{flujo, {}, true}}, std::nullopt};
    }

    if (const auto porque = validar(*cabeceras); !porque.empty()) return cortar(flujo, porque);

    const auto cuanto = declarado(*cabeceras);
    if (!cuanto) return cortar(flujo, cuanto.error());
    if (fin && cuanto->value_or(0) != 0) {
        return cortar(flujo, "content-length sin cuerpo que lo respalde");
    }
    f.declarado = *cuanto;

    return Digerido{{Peticion{flujo, std::move(*cabeceras), fin}}, std::nullopt};
}

Resultado<Digerido> Sesion::data(const Trama& t) {
    const auto tamano = static_cast<std::int64_t>(t.carga.size());
    ventana_ -= tamano;
    if (ventana_ < 0) {
        return conexion(Error::ControlDeFlujo, "el cliente se pasó de la ventana de la conexión");
    }

    auto datos = recortar(t);
    if (!datos) return Fallo{datos.error()};

    const auto i = flujos_.find(t.flujo);
    if (i == flujos_.end() && t.flujo > ultimo_) {
        return conexion(Error::Protocolo, "DATA sobre un flujo que nunca se abrió");
    }

    Digerido d;
    if (tamano > 0) {
        ventana_ += tamano;
        d.acciones.emplace_back(Escribir{Trama::ventana(0, static_cast<std::uint32_t>(tamano))});
    }
    const auto cerrado = [&](std::string_view porque) {
        d.acciones.emplace_back(Escribir{Trama::rst(t.flujo, Error::FlujoCerrado)});
        d.cortado = Cortado{t.flujo, Error::FlujoCerrado, porque};
        return d;
    };
    if (i == flujos_.end()) return cerrado("DATA sobre un flujo cerrado");

    auto& f = i->second;
    f.ventana -= tamano;
    if (f.ventana < 0) {
        return conexion(Error::ControlDeFlujo, "el cliente se pasó de la ventana del flujo");
    }
    if (f.estado == Estado::MitadCerradoRemoto) {
        flujos_.erase(i);
        return cerrado("DATA tras fin de flujo");
    }
    if (t.fin_flujo()) f.estado = Estado::MitadCerradoRemoto;

    f.recibido += datos->size();
    if (f.declarado &&
        (f.recibido > *f.declarado || (t.fin_flujo() && f.recibido != *f.declarado))) {
        auto corte = cortar(t.flujo, "el cuerpo no mide lo que dijo content-length");
        corte.acciones.insert(corte.acciones.begin(), d.acciones.begin(), d.acciones.end());
        return corte;
    }

    d.acciones.insert(d.acciones.begin(), Cuerpo{t.flujo, std::move(*datos), t.fin_flujo()});
    if (tamano > 0 && !t.fin_flujo()) {
        f.ventana += tamano;
        d.acciones.emplace_back(Escribir{Trama::ventana(t.flujo, static_cast<std::uint32_t>(tamano))});
    }
    return d;
}

}
