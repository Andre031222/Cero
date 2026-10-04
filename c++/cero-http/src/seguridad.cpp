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

namespace cero {
namespace {

std::string minuscula(std::string_view s) {
    std::string r{s};
    std::ranges::transform(r, r.begin(), [](unsigned char c) { return std::tolower(c); });
    return r;
}

std::string unir(const std::vector<std::string>& partes) {
    std::string r;
    for (const auto& p : partes) r += (r.empty() ? "" : ", ") + p;
    return r;
}

}  // namespace

Pares Proteccion::aplicar(bool seguro) const {
    Pares h{
        {"X-Content-Type-Options", "nosniff"},                        // SEG-001
        {"X-Frame-Options", enmarcado},                               // SEG-002
        {"Referrer-Policy", "strict-origin-when-cross-origin"},       // SEG-003
        {"Permissions-Policy", "camera=(), microphone=(), geolocation=()"},  // SEG-004
    };
    // SEG-005: sin TLS no se manda HSTS. Prometer transporte seguro sobre texto plano es una
    // promesa que no se puede cumplir, y el navegador la recuerda durante un año.
    if (seguro) h.emplace_back("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    if (csp) h.emplace_back("Content-Security-Policy", *csp);
    return h;
}

Cors::Decision Cors::decidir(std::string_view metodo,
                             std::optional<std::string_view> origen) const {
    // SEG-011: sin `Origin` no se añade ninguna cabecera CORS.
    if (!origen) return Sigue{};

    const bool preflight = metodo == "OPTIONS";
    const bool permitido =
        origenes.empty() || std::ranges::find(origenes, *origen) != origenes.end();
    if (!permitido) {
        // SEG-013: el preflight ajeno se rechaza, porque su único propósito es preguntar.
        // SEG-010: la petición simple no se bloquea — ya llegó, y bloquearla daría una falsa
        // sensación de protección. Quien decide es el navegador.
        if (preflight) return Corta{403, {}};
        return Sigue{{{"Vary", "Origin"}}};
    }

    // SEG-014: con comodín y sin credenciales se responde comodín; con credenciales hay que
    // devolver el origen concreto, porque el navegador rechaza `*` junto a credenciales.
    const std::string cual = origenes.empty() && !credenciales ? "*" : std::string{*origen};
    Pares h{
        {"Access-Control-Allow-Origin", cual},
        {"Vary", "Origin"},  // SEG-009: la respuesta depende del origen; las cachés han de saberlo
    };
    if (credenciales) h.emplace_back("Access-Control-Allow-Credentials", "true");
    if (!preflight) return Sigue{std::move(h)};

    // SEG-012: el preflight admitido responde 204 anunciando qué se permite y por cuánto tiempo.
    h.emplace_back("Access-Control-Allow-Methods", unir(metodos));
    h.emplace_back("Access-Control-Allow-Headers", unir(cabeceras));
    h.emplace_back("Access-Control-Max-Age", formato("{}", max_age));
    return Corta{204, std::move(h)};
}

bool exento(std::string_view camino, const std::vector<std::string>& exenciones) {
    return std::ranges::any_of(exenciones, [camino](std::string_view e) {
        while (e.ends_with('/')) e.remove_suffix(1);
        return camino == e || (camino.starts_with(e) && camino.size() > e.size() &&
                               camino[e.size()] == '/');
    });
}

// Comparación en tiempo constante: con `==` el tiempo depende del prefijo común y filtra el token
// carácter a carácter.
namespace {
bool constante(std::string_view a, std::string_view b) {
    if (a.size() != b.size()) return false;
    unsigned char diferencia = 0;
    for (std::size_t i = 0; i < a.size(); ++i) {
        diferencia |= static_cast<unsigned char>(a[i] ^ b[i]);
    }
    return diferencia == 0;
}
}  // namespace

bool csrf_valido(std::string_view metodo, std::string_view camino,
                 const std::vector<std::string>& exenciones,
                 std::optional<std::string_view> token_sesion,
                 std::optional<std::string_view> token_peticion) {
    // SEG-015: un método seguro pasa sin token.
    constexpr std::string_view seguros[]{"GET", "HEAD", "OPTIONS", "TRACE"};
    if (std::ranges::find(seguros, metodo) != std::end(seguros)) return true;
    if (exento(camino, exenciones)) return true;
    // SEG-016 y SEG-018: sin token o con token erróneo, no pasa.
    if (!token_sesion || !token_peticion) return false;
    return constante(*token_sesion, *token_peticion);
}

Veredicto Limitador::pedir(std::string_view cliente) {
    const std::lock_guard tomado{candado_};
    const auto ahora = std::chrono::steady_clock::now();
    auto& cuenta = cuentas_.try_emplace(std::string{cliente}, Cuenta{0, ahora}).first->second;
    if (ahora - cuenta.desde > ventana_) cuenta = Cuenta{0, ahora};
    ++cuenta.usadas;

    const auto queda = ventana_ - std::chrono::duration_cast<std::chrono::seconds>(ahora - cuenta.desde);
    return {cuenta.usadas <= cupo_, cupo_,
            cuenta.usadas >= cupo_ ? 0 : cupo_ - cuenta.usadas,
            static_cast<unsigned>(std::max<long long>(queda.count(), 0)) + 1};
}

std::size_t Limitador::claves() const {
    const std::lock_guard tomado{candado_};
    return cuentas_.size();
}

// SEG-020 y SEG-021: el 429 lleva `Retry-After`, y toda respuesta anuncia límite y restante.
Pares cabeceras_limite(const Veredicto& v) {
    Pares h{{"X-RateLimit-Limit", formato("{}", v.limite)},
            {"X-RateLimit-Remaining", formato("{}", v.restante)}};
    if (!v.permitida) h.emplace_back("Retry-After", formato("{}", v.reintentar_en));
    return h;
}

namespace {

constexpr std::string_view kProhibidas[]{"script", "style", "iframe", "object", "embed"};

// Las dos formas de ejecutar sin un `<script>`: un manejador de evento y el protocolo
// `javascript:`. Se corta la etiqueta donde empieza el atributo en vez de borrarla entera, que es
// lo que `SEG-024` pide — un saneador que borra todo se desactiva, y entonces no sanea nada.
std::string limpiar_atributos(std::string_view etiqueta) {
    const auto bajo = minuscula(etiqueta);
    if (const auto i = bajo.find(" on"); i != std::string::npos && bajo.contains('=')) {
        return std::string{etiqueta.substr(0, i)} + ">";
    }
    if (bajo.contains("javascript:")) {
        const auto i = bajo.find(' ');
        return std::string{etiqueta.substr(0, i == std::string::npos ? etiqueta.size() - 1 : i)} + ">";
    }
    return std::string{etiqueta};
}

}  // namespace

std::string sanear_html(std::string_view entrada) {
    std::string salida;
    salida.reserve(entrada.size());
    std::string_view resto = entrada;
    while (true) {
        const auto i = resto.find('<');
        if (i == std::string_view::npos) break;
        salida += resto.substr(0, i);
        resto.remove_prefix(i);
        const auto fin = resto.find('>');
        if (fin == std::string_view::npos) break;

        std::string_view etiqueta = resto.substr(1, fin - 1);
        while (etiqueta.starts_with('/')) etiqueta.remove_prefix(1);
        std::string nombre;
        for (char c : etiqueta) {
            if (std::isalnum(static_cast<unsigned char>(c)) == 0) break;
            nombre += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        }

        if (std::ranges::find(kProhibidas, nombre) != std::end(kProhibidas)) {
            // Se descarta la etiqueta **y su contenido**: dejar fuera el texto de un `<script>`
            // basta para que otro contexto lo vuelva a ejecutar.
            const auto cierre = "</" + nombre + ">";
            const auto donde = minuscula(resto).find(cierre);
            resto = donde == std::string::npos ? std::string_view{} : resto.substr(donde + cierre.size());
            continue;
        }
        salida += limpiar_atributos(resto.substr(0, fin + 1));
        resto.remove_prefix(fin + 1);
    }
    salida += resto;
    return salida;
}

// SEG-025: a texto plano no queda ninguna etiqueta, y del contenido de `script` no queda rastro.
std::string sanear_texto(std::string_view entrada) {
    const auto html = sanear_html(entrada);
    std::string salida;
    bool dentro = false;
    for (char c : html) {
        if (c == '<') dentro = true;
        else if (c == '>') dentro = false;
        else if (!dentro) salida += c;
    }
    return salida;
}

// SEG-026: quita rutas y separadores de los dos sistemas, y nunca devuelve vacío.
std::string sanear_nombre(std::string_view entrada) {
    const auto corte = entrada.find_last_of("/\\");
    const auto base = corte == std::string_view::npos ? entrada : entrada.substr(corte + 1);
    std::string limpio;
    for (char c : base) {
        const auto u = static_cast<unsigned char>(c);
        if (std::isalnum(u) != 0 || std::string_view{"._- "}.contains(c)) limpio += c;
    }
    const auto desde = limpio.find_first_not_of(". ");
    const auto hasta = limpio.find_last_not_of(". ");
    if (desde == std::string::npos) return "archivo";
    limpio = limpio.substr(desde, hasta - desde + 1);
    return limpio.empty() ? "archivo" : limpio;
}

}  // namespace cero
