#include "cero/json.hpp"

#include <cctype>
#include <charconv>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>
#include <variant>
#include <vector>

#include "cero/formato.hpp"

namespace cero {
namespace {

// Tope de anidamiento. Cinco mil corchetes son diez kilobytes de cuerpo y desbordan la pila de un
// lector recursivo: denegación de servicio con una petición pequeña y perfectamente válida.
constexpr int kMaxHondura = 200;

using Fallo = std::unexpected<FalloJson>;

std::string escapar(std::string_view s) {
    std::string r;
    r.reserve(s.size() + 2);
    for (char c : s) {
        switch (c) {
            case '"': r += "\\\""; break;
            case '\\': r += "\\\\"; break;
            case '\n': r += "\\n"; break;
            case '\r': r += "\\r"; break;
            case '\t': r += "\\t"; break;
            // Un JSON incrustado en una página que contenga `</script` cierra la etiqueta, y lo
            // que siga se ejecuta: es XSS a través de una respuesta perfectamente válida.
            case '<': r += "\\u003c"; break;
            case '>': r += "\\u003e"; break;
            case '&': r += "\\u0026"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    r += formato("\\u{:04x}", static_cast<unsigned>(static_cast<unsigned char>(c)));
                } else {
                    r += c;
                }
        }
    }
    return r;
}

std::string numero_a_texto(double v) {
    if (v == static_cast<double>(static_cast<long long>(v))) {
        return formato("{}", static_cast<long long>(v));
    }
    return formato("{}", v);
}

class Lector {
public:
    explicit Lector(std::string_view entrada) : s_(entrada) {}

    std::expected<Json, FalloJson> todo() {
        auto v = valor(0);
        if (!v) return v;
        blancos();
        if (i_ != s_.size()) return Fallo{{"sobra texto tras el valor"}};
        return v;
    }

private:
    void blancos() {
        while (i_ < s_.size() && (s_[i_] == ' ' || s_[i_] == '\t' || s_[i_] == '\n' || s_[i_] == '\r')) {
            ++i_;
        }
    }

    bool consume(char c) {
        blancos();
        if (i_ < s_.size() && s_[i_] == c) {
            ++i_;
            return true;
        }
        return false;
    }

    std::expected<Json, FalloJson> valor(int hondura) {
        if (hondura > kMaxHondura) return Fallo{{"demasiado anidamiento"}};
        blancos();
        if (i_ >= s_.size()) return Fallo{{"se acabó la entrada"}};
        switch (s_[i_]) {
            case '{': return objeto(hondura);
            case '[': return lista(hondura);
            case '"': {
                auto t = cadena();
                if (!t) return Fallo{t.error()};
                return Json{*t};
            }
            case 't':
                if (s_.substr(i_).starts_with("true")) {
                    i_ += 4;
                    return Json{true};
                }
                return Fallo{{"literal desconocido"}};
            case 'f':
                if (s_.substr(i_).starts_with("false")) {
                    i_ += 5;
                    return Json{false};
                }
                return Fallo{{"literal desconocido"}};
            case 'n':
                if (s_.substr(i_).starts_with("null")) {
                    i_ += 4;
                    return Json{};
                }
                return Fallo{{"literal desconocido"}};
            default: return numero();
        }
    }

    std::expected<std::string, FalloJson> cadena() {
        if (!consume('"')) return Fallo{{"se esperaba una cadena"}};
        std::string r;
        while (i_ < s_.size() && s_[i_] != '"') {
            if (s_[i_] != '\\') {
                r += s_[i_++];
                continue;
            }
            if (++i_ >= s_.size()) return Fallo{{"escape a medias"}};
            switch (s_[i_++]) {
                case '"': r += '"'; break;
                case '\\': r += '\\'; break;
                case '/': r += '/'; break;
                case 'b': r += '\b'; break;
                case 'f': r += '\f'; break;
                case 'n': r += '\n'; break;
                case 'r': r += '\r'; break;
                case 't': r += '\t'; break;
                case 'u': {
                    if (i_ + 4 > s_.size()) return Fallo{{"\\u incompleto"}};
                    unsigned punto = 0;
                    const auto desde = s_.data() + i_;
                    if (std::from_chars(desde, desde + 4, punto, 16).ec != std::errc{}) {
                        return Fallo{{"\\u no hexadecimal"}};
                    }
                    i_ += 4;
                    // UTF-8 a mano: el plano básico basta, y los pares subrogados se dejan como
                    // el carácter de sustitución en vez de inventar un acoplamiento.
                    if (punto < 0x80) {
                        r += static_cast<char>(punto);
                    } else if (punto < 0x800) {
                        r += static_cast<char>(0xc0 | (punto >> 6));
                        r += static_cast<char>(0x80 | (punto & 0x3f));
                    } else {
                        r += static_cast<char>(0xe0 | (punto >> 12));
                        r += static_cast<char>(0x80 | ((punto >> 6) & 0x3f));
                        r += static_cast<char>(0x80 | (punto & 0x3f));
                    }
                    break;
                }
                default: return Fallo{{"escape desconocido"}};
            }
        }
        if (i_ >= s_.size()) return Fallo{{"cadena sin cerrar"}};
        ++i_;
        return r;
    }

    std::expected<Json, FalloJson> numero() {
        const auto desde = i_;
        if (i_ < s_.size() && (s_[i_] == '-' || s_[i_] == '+')) ++i_;
        while (i_ < s_.size() && (std::isdigit(static_cast<unsigned char>(s_[i_])) != 0 ||
                                  s_[i_] == '.' || s_[i_] == 'e' || s_[i_] == 'E' ||
                                  s_[i_] == '-' || s_[i_] == '+')) {
            ++i_;
        }
        const auto trozo = s_.substr(desde, i_ - desde);
        if (trozo.empty()) return Fallo{{"no es un valor"}};
        double v = 0;
        const auto fin = trozo.data() + trozo.size();
        const auto leido = std::from_chars(trozo.data(), fin, v);
        if (leido.ec != std::errc{} || leido.ptr != fin) return Fallo{{"número mal formado"}};
        return Json{v};
    }

    std::expected<Json, FalloJson> lista(int hondura) {
        consume('[');
        Json::Lista items;
        if (consume(']')) return Json{std::move(items)};
        while (true) {
            auto v = valor(hondura + 1);
            if (!v) return v;
            items.push_back(std::move(*v));
            if (consume(']')) return Json{std::move(items)};
            if (!consume(',')) return Fallo{{"se esperaba , o ]"}};
        }
    }

    std::expected<Json, FalloJson> objeto(int hondura) {
        consume('{');
        Json::Objeto pares;
        if (consume('}')) return Json{std::move(pares)};
        while (true) {
            blancos();
            auto clave = cadena();
            if (!clave) return Fallo{clave.error()};
            if (!consume(':')) return Fallo{{"se esperaba :"}};
            auto v = valor(hondura + 1);
            if (!v) return v;
            pares.insert_or_assign(std::move(*clave), std::move(*v));
            if (consume('}')) return Json{std::move(pares)};
            if (!consume(',')) return Fallo{{"se esperaba , o }"}};
        }
    }

    std::string_view s_;
    std::size_t i_ = 0;
};

}  // namespace

Json Json::objeto(std::vector<std::pair<std::string, Json>> pares) {
    Objeto m;
    for (auto& [clave, v] : pares) m.insert_or_assign(std::move(clave), std::move(v));
    return Json{std::move(m)};
}

const Json* Json::get(std::string_view clave) const {
    const auto* m = std::get_if<Objeto>(&valor_);
    if (m == nullptr) return nullptr;
    const auto i = m->find(clave);
    return i == m->end() ? nullptr : &i->second;
}

std::optional<std::string_view> Json::cadena() const {
    if (const auto* v = std::get_if<std::string>(&valor_)) return *v;
    return std::nullopt;
}

std::optional<double> Json::numero() const {
    if (const auto* v = std::get_if<double>(&valor_)) return *v;
    return std::nullopt;
}

std::optional<long long> Json::entero() const {
    const auto* v = std::get_if<double>(&valor_);
    if (v == nullptr || *v != static_cast<double>(static_cast<long long>(*v))) return std::nullopt;
    return static_cast<long long>(*v);
}

std::optional<bool> Json::booleano() const {
    if (const auto* v = std::get_if<bool>(&valor_)) return *v;
    return std::nullopt;
}

const Json::Lista* Json::lista() const { return std::get_if<Lista>(&valor_); }
const Json::Objeto* Json::como_objeto() const { return std::get_if<Objeto>(&valor_); }

std::string Json::escribir() const {
    return std::visit(
        [](const auto& v) -> std::string {
            using T = std::decay_t<decltype(v)>;
            if constexpr (std::is_same_v<T, Nulo>) {
                return "null";
            } else if constexpr (std::is_same_v<T, bool>) {
                return v ? "true" : "false";
            } else if constexpr (std::is_same_v<T, double>) {
                return numero_a_texto(v);
            } else if constexpr (std::is_same_v<T, std::string>) {
                return formato("\"{}\"", escapar(v));
            } else if constexpr (std::is_same_v<T, Lista>) {
                std::string r = "[";
                for (const auto& x : v) r += (r.size() == 1 ? "" : ",") + x.escribir();
                return r + "]";
            } else {
                std::string r = "{";
                for (const auto& [clave, x] : v) {
                    r += (r.size() == 1 ? "" : ",") + formato("\"{}\":{}", escapar(clave), x.escribir());
                }
                return r + "}";
            }
        },
        valor_);
}

std::expected<Json, FalloJson> leer(std::string_view entrada) {
    return Lector{entrada}.todo();
}

}  // namespace cero
