#pragma once

#include <expected>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <string_view>

namespace cero {

// De dónde salen los octetos. Un socket en producción y una cadena en las pruebas: el parser no
// sabe cuál, y por eso los 23 vectores del banco se corren sin abrir un puerto.
class Lector {
public:
    virtual ~Lector() = default;
    virtual int octeto() = 0;  // -1 cuando se acabó
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

// El estado lo fija el RFC, no el sitio de la llamada, así que viaja con el motivo.
enum class Rechazo { MalFormada, NoImplementado, VersionNoSoportada, Demasiado };

constexpr unsigned estado_de(Rechazo r) {
    switch (r) {
        case Rechazo::NoImplementado: return 501;
        case Rechazo::VersionNoSoportada: return 505;
        case Rechazo::Demasiado: return 431;
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

    // Vista sobre `destino`, sin copiar: el camino siempre es un trozo de lo que llegó.
    std::string_view camino() const;
};

std::expected<Peticion, Rechazo> leer(Lector& lector);

}  // namespace cero
