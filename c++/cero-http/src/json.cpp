#include "cero/json.hpp"

#include <cctype>
#include <charconv>
#include <cmath>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>
#include <variant>
#include <vector>

#include "cero/formato.hpp"

using namespace std;

namespace cero {
namespace {

constexpr int kMaxHondura = 200;

using Fallo = unexpected<FalloJson>;

string escapar(string_view s) {
    string r;
    r.reserve(s.size() + 2);
    for (char c : s) {
        switch (c) {
            case '"': r += "\\\""; break;
            case '\\': r += "\\\\"; break;
            case '\n': r += "\\n"; break;
            case '\r': r += "\\r"; break;
            case '\t': r += "\\t"; break;
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

string numero_a_texto(double v) {
    if (v == static_cast<double>(static_cast<long long>(v))) {
        return formato("{}", static_cast<long long>(v));
    }
    return formato("{}", v);
}

class Lector {
public:
    explicit Lector(string_view entrada) : s_(entrada) {}

    expected<Json, FalloJson> todo() {
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

    expected<Json, FalloJson> valor(int hondura) {
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

    expected<string, FalloJson> cadena() {
        if (!consume('"')) return Fallo{{"se esperaba una cadena"}};
        string r;
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
                    if (from_chars(desde, desde + 4, punto, 16).ec != errc{}) {
                        return Fallo{{"\\u no hexadecimal"}};
                    }
                    i_ += 4;
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

    expected<Json, FalloJson> numero() {
        const auto desde = i_;
        if (i_ < s_.size() && (s_[i_] == '-' || s_[i_] == '+')) ++i_;
        while (i_ < s_.size() && (isdigit(static_cast<unsigned char>(s_[i_])) != 0 ||
                                  s_[i_] == '.' || s_[i_] == 'e' || s_[i_] == 'E' ||
                                  s_[i_] == '-' || s_[i_] == '+')) {
            ++i_;
        }
        const auto trozo = s_.substr(desde, i_ - desde);
        if (trozo.empty()) return Fallo{{"no es un valor"}};
        double v = 0;
        const auto fin = trozo.data() + trozo.size();
        const auto leido = from_chars(trozo.data(), fin, v);
        if (leido.ec != errc{} || leido.ptr != fin) return Fallo{{"número mal formado"}};
        return Json{v};
    }

    expected<Json, FalloJson> lista(int hondura) {
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

    expected<Json, FalloJson> objeto(int hondura) {
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

    string_view s_;
    size_t i_ = 0;
};

}

Json Json::objeto(vector<pair<string, Json>> pares) {
    Objeto m;
    for (auto& [clave, v] : pares) m.insert_or_assign(std::move(clave), std::move(v));
    return Json{std::move(m)};
}

const Json* Json::get(string_view clave) const {
    const auto* m = get_if<Objeto>(&valor_);
    if (m == nullptr) return nullptr;
    const auto i = m->find(clave);
    return i == m->end() ? nullptr : &i->second;
}

optional<string_view> Json::cadena() const {
    if (const auto* v = get_if<string>(&valor_)) return *v;
    return nullopt;
}

optional<double> Json::numero() const {
    if (const auto* v = get_if<double>(&valor_)) return *v;
    return nullopt;
}

optional<long long> Json::entero() const {
    const auto* v = get_if<double>(&valor_);
    if (v == nullptr || !(*v >= -0x1p63 && *v < 0x1p63) || *v != trunc(*v)) return nullopt;
    return static_cast<long long>(*v);
}

optional<bool> Json::booleano() const {
    if (const auto* v = get_if<bool>(&valor_)) return *v;
    return nullopt;
}

const Json::Lista* Json::lista() const { return get_if<Lista>(&valor_); }
const Json::Objeto* Json::como_objeto() const { return get_if<Objeto>(&valor_); }

string Json::escribir() const {
    return visit(
        [](const auto& v) -> string {
            using T = decay_t<decltype(v)>;
            if constexpr (is_same_v<T, Nulo>) {
                return "null";
            } else if constexpr (is_same_v<T, bool>) {
                return v ? "true" : "false";
            } else if constexpr (is_same_v<T, double>) {
                return numero_a_texto(v);
            } else if constexpr (is_same_v<T, string>) {
                return formato("\"{}\"", escapar(v));
            } else if constexpr (is_same_v<T, Lista>) {
                string r = "[";
                for (const auto& x : v) r += (r.size() == 1 ? "" : ",") + x.escribir();
                return r + "]";
            } else {
                string r = "{";
                for (const auto& [clave, x] : v) {
                    r += (r.size() == 1 ? "" : ",") + formato("\"{}\":{}", escapar(clave), x.escribir());
                }
                return r + "}";
            }
        },
        valor_);
}

expected<Json, FalloJson> leer(string_view entrada) {
    return Lector{entrada}.todo();
}

}
