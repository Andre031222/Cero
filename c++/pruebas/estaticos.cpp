// Servir un directorio es la primera forma de abrir un agujero. Estas pruebas son el intento.

#include <filesystem>
#include <fstream>
#include <string>
#include <string_view>
#include <utility>

#include "cero/estaticos.hpp"
#include "prueba.hpp"

namespace {

namespace fs = std::filesystem;

void escribir(const fs::path& donde, std::string_view que) {
    std::ofstream{donde, std::ios::binary} << que;
}

// Un árbol **por prueba**. Con uno compartido, las que corren a la vez truncan los ficheros de las
// otras y el fallo aparece en una máquina y no en otra. Pasó en Rust y se arregló allí.
fs::path raiz(std::string_view prueba) {
    const auto caja = fs::temp_directory_path() / ("cero-est-cpp-" + std::string{prueba});
    const auto d = caja / "raiz";
    fs::create_directories(d / "sub");
    escribir(d / "index.html", "<h1>portada</h1>");
    escribir(d / "estilo.css", "body{}");
    escribir(d / "sub" / "hondo.txt", "hondo");
    // Justo fuera de la raíz, que es donde apunta el `..` que las pruebas intentan.
    escribir(caja / "cero-secreto.txt", "no deberías ver esto");
    return d;
}

cero::Estaticos estaticos(std::string_view prueba) {
    return *cero::Estaticos::en(raiz(prueba).string());
}

bool dice(const cero::Respuesta& r, std::string_view que) {
    return r.cuerpo.find(que) != std::string::npos;
}

PRUEBA(sirve_lo_que_hay_con_su_tipo) {
    const auto e = estaticos("sirve");
    const auto r = e.servir("/index.html");
    COMPRUEBA(r.estado == 200, "sirve");
    COMPRUEBA(r.tipo.starts_with("text/html"), r.tipo);
    COMPRUEBA(dice(r, "portada"), "con su contenido");
    COMPRUEBA(e.servir("/estilo.css").tipo.starts_with("text/css"), "y su tipo");
    COMPRUEBA(e.servir("/sub/hondo.txt").estado == 200, "los subdirectorios también");
}

PRUEBA(lo_que_no_esta_da_404) {
    COMPRUEBA(estaticos("no-esta").servir("/no-existe.txt").estado == 404, "404");
}

PRUEBA(no_se_puede_salir_de_la_raiz) {
    const auto e = estaticos("salir");
    for (std::string_view intento : {"/../cero-secreto.txt", "/../../etc/passwd",
                                     "/sub/../../cero-secreto.txt", "//etc/passwd",
                                     "/./../../cero-secreto.txt"}) {
        const auto r = e.servir(intento);
        COMPRUEBA(r.estado != 200, intento);
        COMPRUEBA(!dice(r, "no deberías"), intento);
    }
}

PRUEBA(el_respaldo_atiende_las_rutas_de_cliente) {
    auto suelto = *cero::Estaticos::en(raiz("respaldo").string());
    const auto e = std::move(suelto).con_respaldo("index.html");
    // Una aplicación de una sola página resuelve sus rutas en el navegador: el servidor devuelve
    // la portada y deja que el cliente decida.
    const auto r = e.servir("/panel/usuarios/7");
    COMPRUEBA(r.estado == 200, "el respaldo atiende");
    COMPRUEBA(dice(r, "portada"), "con la portada");

    // Con respaldo **todo** devuelve 200 por diseño: esa es su razón de ser. Lo que no puede pasar
    // es que salga contenido de fuera de la raíz, y eso es lo que se afirma.
    const auto intento = e.servir("/../cero-secreto.txt");
    COMPRUEBA(!dice(intento, "no deberías"), "el respaldo no es una forma de saltarse la raíz");
    COMPRUEBA(dice(intento, "portada"), "lo que sale es el respaldo, no el secreto");
}

PRUEBA(lo_desconocido_no_se_adivina) {
    const auto d = raiz("desconocido");
    escribir(d / "raro.xyz", "datos");
    const auto e = *cero::Estaticos::en(d.string());
    COMPRUEBA(e.servir("/raro.xyz").tipo == "application/octet-stream",
              "adivinar el tipo es lo que nosniff existe para impedir");
}

PRUEBA(una_raiz_que_no_existe_no_construye_nada) {
    // Detectado al arrancar es un error de configuración; detectado al servir es un 500 en
    // producción por cada petición de estáticos.
    COMPRUEBA(!cero::Estaticos::en("/no/existe/esta/raiz").has_value(), "no se construye");
}

}  // namespace

PRUEBAS_MAIN
