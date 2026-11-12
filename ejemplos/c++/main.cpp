#include <charconv>
#include <cstdlib>
#include <string>
#include <string_view>
#include <system_error>

#include "cero/servidor.hpp"

using namespace std;
using namespace cero;

unsigned short puerto() {
    const string_view texto = getenv("PUERTO") ? getenv("PUERTO") : "";
    unsigned short valor = 0;
    const auto [fin, error] = from_chars(texto.data(), texto.data() + texto.size(), valor);
    return error == errc{} && fin == texto.data() + texto.size() && valor > 0 ? valor : 8080;
}

int main() {
    return Servidor{}
        .get("/", [](const Contexto&) { return Json::objeto({{"hola", "mundo"}}); })
        .get("/saludo/{nombre}", [](const Contexto& c) {
            return Json::objeto({{"saludo", "hola, " + string{*c.variable("nombre")}}});
        })
        .post("/eco", [](const Contexto& c) { return c.cuerpo_json(); })
        .direccion("127.0.0.1")
        .escuchar(puerto());
}
