#include <cstdlib>
#include <string>

#include "cero/servidor.hpp"

using namespace std;
using namespace cero;

int main() {
    const char* puerto = getenv("PUERTO");
    return Servidor{}
        .get("/", [](const Contexto&) { return Json::objeto({{"hola", "mundo"}}); })
        .get("/saludo/{nombre}", [](const Contexto& c) {
            return Json::objeto({{"saludo", "hola, " + string{*c.variable("nombre")}}});
        })
        .post("/eco", [](const Contexto& c) { return c.cuerpo_json(); })
        .direccion("127.0.0.1")
        .escuchar(puerto ? stoi(puerto) : 8080);
}
