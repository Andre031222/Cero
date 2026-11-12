#include "cero/seguridad.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "cero/formato.hpp"

using namespace std;

namespace cero {
namespace {

string unir(const Textos& partes) {
    string r;
    for (const auto& p : partes) r += (r.empty() ? "" : ", ") + p;
    return r;
}

}

Pares Proteccion::aplicar(bool seguro) const {
    Pares h{
        {"X-Content-Type-Options", "nosniff"},
        {"X-Frame-Options", enmarcado},
        {"Referrer-Policy", "strict-origin-when-cross-origin"},
        {"Permissions-Policy", "camera=(), microphone=(), geolocation=()"},
    };
    if (seguro) h.emplace_back("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    if (csp) h.emplace_back("Content-Security-Policy", *csp);
    return h;
}

Cors::Decision Cors::decidir(string_view metodo, optional<string_view> origen) const {
    if (!origen) return Sigue{};

    const bool preflight = metodo == "OPTIONS";
    const bool permitido =
        origenes.empty() || ranges::find(origenes, *origen) != origenes.end();
    if (!permitido) {
        if (preflight) return Corta{403, {}};
        return Sigue{{{"Vary", "Origin"}}};
    }

    const string cual = origenes.empty() && !credenciales ? "*" : string{*origen};
    Pares h{
        {"Access-Control-Allow-Origin", cual},
        {"Vary", "Origin"},
    };
    if (credenciales) h.emplace_back("Access-Control-Allow-Credentials", "true");
    if (!preflight) return Sigue{std::move(h)};

    h.emplace_back("Access-Control-Allow-Methods", unir(metodos));
    h.emplace_back("Access-Control-Allow-Headers", unir(cabeceras));
    h.emplace_back("Access-Control-Max-Age", formato("{}", max_age));
    return Corta{204, std::move(h)};
}

bool exento(string_view camino, const Textos& exenciones) {
    return ranges::any_of(exenciones, [camino](string_view e) {
        while (e.ends_with('/')) e.remove_suffix(1);
        return camino == e || (camino.starts_with(e) && camino.size() > e.size() &&
                               camino[e.size()] == '/');
    });
}

namespace {
bool constante(string_view a, string_view b) {
    if (a.size() != b.size()) return false;
    unsigned char diferencia = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        diferencia |= static_cast<unsigned char>(a[i] ^ b[i]);
    }
    return diferencia == 0;
}
}

bool csrf_valido(string_view metodo, string_view camino, const Textos& exenciones,
                 optional<string_view> token_sesion, optional<string_view> token_peticion) {
    constexpr string_view seguros[]{"GET", "HEAD", "OPTIONS", "TRACE"};
    if (ranges::find(seguros, metodo) != end(seguros)) return true;
    if (exento(camino, exenciones)) return true;
    if (!token_sesion || !token_peticion) return false;
    return constante(*token_sesion, *token_peticion);
}

Veredicto Limitador::pedir(string_view cliente) {
    const lock_guard tomado{candado_};
    const auto ahora = chrono::steady_clock::now();
    auto& cuenta = cuentas_.try_emplace(string{cliente}, Cuenta{0, ahora}).first->second;
    if (ahora - cuenta.desde > ventana_) cuenta = Cuenta{0, ahora};
    ++cuenta.usadas;

    const auto queda = ventana_ - chrono::duration_cast<chrono::seconds>(ahora - cuenta.desde);
    return {cuenta.usadas <= cupo_, cupo_,
            cuenta.usadas >= cupo_ ? 0 : cupo_ - cuenta.usadas,
            static_cast<unsigned>(max<long long>(queda.count(), 0)) + 1};
}

size_t Limitador::claves() const {
    const lock_guard tomado{candado_};
    return cuentas_.size();
}

Pares cabeceras_limite(const Veredicto& v) {
    Pares h{{"X-RateLimit-Limit", formato("{}", v.limite)},
            {"X-RateLimit-Remaining", formato("{}", v.restante)}};
    if (!v.permitida) h.emplace_back("Retry-After", formato("{}", v.reintentar_en));
    return h;
}

namespace {

constexpr string_view kProhibidas[]{"script", "style", "iframe", "object", "embed"};

string limpiar_atributos(string_view etiqueta) {
    const auto bajo = minuscula(etiqueta);
    if (const auto i = bajo.find(" on"); i != string::npos && bajo.contains('=')) {
        return string{etiqueta.substr(0, i)} + ">";
    }
    if (bajo.contains("javascript:")) {
        const auto i = bajo.find(' ');
        return string{etiqueta.substr(0, i == string::npos ? etiqueta.size() - 1 : i)} + ">";
    }
    return string{etiqueta};
}

}

string sanear_html(string_view entrada) {
    string salida;
    salida.reserve(entrada.size());
    string_view resto = entrada;
    while (true) {
        const auto i = resto.find('<');
        if (i == string_view::npos) break;
        salida += resto.substr(0, i);
        resto.remove_prefix(i);
        const auto fin = resto.find('>');
        if (fin == string_view::npos) break;

        string_view etiqueta = resto.substr(1, fin - 1);
        while (etiqueta.starts_with('/')) etiqueta.remove_prefix(1);
        string nombre;
        for (char c : etiqueta) {
            if (isalnum(static_cast<unsigned char>(c)) == 0) break;
            nombre += static_cast<char>(tolower(static_cast<unsigned char>(c)));
        }

        if (ranges::find(kProhibidas, nombre) != end(kProhibidas)) {
            const auto cierre = "</" + nombre + ">";
            const auto donde = minuscula(resto).find(cierre);
            resto = donde == string::npos ? string_view{} : resto.substr(donde + cierre.size());
            continue;
        }
        salida += limpiar_atributos(resto.substr(0, fin + 1));
        resto.remove_prefix(fin + 1);
    }
    salida += resto;
    return salida;
}

string sanear_texto(string_view entrada) {
    const auto html = sanear_html(entrada);
    string salida;
    bool dentro = false;
    for (char c : html) {
        if (c == '<') dentro = true;
        else if (c == '>') dentro = false;
        else if (!dentro) salida += c;
    }
    return salida;
}

string sanear_nombre(string_view entrada) {
    const auto corte = entrada.find_last_of("/\\");
    const auto base = corte == string_view::npos ? entrada : entrada.substr(corte + 1);
    string limpio;
    for (char c : base) {
        const auto u = static_cast<unsigned char>(c);
        if (isalnum(u) != 0 || string_view{"._- "}.contains(c)) limpio += c;
    }
    const auto desde = limpio.find_first_not_of(". ");
    const auto hasta = limpio.find_last_not_of(". ");
    if (desde == string::npos) return "archivo";
    limpio = limpio.substr(desde, hasta - desde + 1);
    return limpio.empty() ? "archivo" : limpio;
}

}
