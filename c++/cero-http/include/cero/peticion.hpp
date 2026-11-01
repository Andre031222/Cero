#pragma once

#include <cstddef>
#include <expected>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <string_view>

namespace cero {

class Lector {
public:
    virtual ~Lector() = default;
    virtual int octeto() = 0;
};

class DesdeTexto final : public Lector {
public:
    explicit DesdeTexto(std::string_view texto) : texto_(texto) {}
    int octeto() override {
        return i_ < texto_.size() ? static_cast<unsigned char>(texto_[i_++]) : -1;
    }

private:
    std::string texto_;
    std::size_t i_ = 0;
};

enum class Rechazo { MalFormada, NoImplementado, VersionNoSoportada, Demasiado, CuerpoGrande };

inline constexpr std::size_t kMaxCuerpo = 10 * 1024 * 1024;

constexpr unsigned estado_de(Rechazo r) {
    switch (r) {
        case Rechazo::NoImplementado: return 501;
        case Rechazo::VersionNoSoportada: return 505;
        case Rechazo::Demasiado: return 431;
        case Rechazo::CuerpoGrande: return 413;
        case Rechazo::MalFormada: break;
    }
    return 400;
}

struct Peticion {
    std::string metodo;
    std::string destino;
    std::string version;
    std::map<std::string, std::string, std::less<>> cabeceras;
    std::string cuerpo;

    std::optional<std::string_view> cabecera(std::string_view nombre) const;

    std::string_view camino() const;
};

bool metodo_conocido(std::string_view metodo);

std::expected<Peticion, Rechazo> leer(Lector& lector, std::size_t max_cuerpo = kMaxCuerpo);

}
