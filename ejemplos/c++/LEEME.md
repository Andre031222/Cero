# Empezar en C++

La misma API pequeña que en los otros lenguajes, con Cero traído por `FetchContent`. Hace falta
CMake 3.28 y un compilador con C++23 (Clang 17 o GCC 13 en adelante). No hay nada más que
instalar: Cero no tiene dependencias.

La implementación en C++ es **solo POSIX** por ahora (Linux, macOS, los BSD): los sockets se
llaman por POSIX, porque el estándar no trae ninguno. En Windows no compila todavía; el soporte
está previsto. Mientras, sirve WSL.

## Instalar

En el `CMakeLists.txt` del proyecto:

```cmake
include(FetchContent)
FetchContent_Declare(cero
    GIT_REPOSITORY https://github.com/Andre031222/Cero
    GIT_TAG main
    GIT_SHALLOW TRUE
    SOURCE_SUBDIR c++)
FetchContent_MakeAvailable(cero)

target_link_libraries(app PRIVATE cero::http)
```

`cero::http` es el framework y trae C++23 consigo; `cero::data` añade las sesiones en disco. Si
Cero ya está en una carpeta del proyecto, `add_subdirectory(cero/c++)` da los mismos dos objetivos.
Las pruebas de Cero y su servidor del banco solo se compilan cuando Cero es el proyecto principal.

`GIT_TAG main` funciona en cuanto este código esté en la rama `main` de GitHub. Hasta entonces, o
para usar una copia local, se le indica a CMake dónde está el repositorio y no descarga nada:

```bash
cmake -S . -B build -DFETCHCONTENT_SOURCE_DIR_CERO=<ruta-al-repositorio-de-Cero>
```

## El ejemplo

[`main.cpp`](main.cpp) entero, una ruta por línea:

```cpp
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
```

| Petición | Respuesta |
|---|---|
| `GET /` | `{"hola":"mundo"}` |
| `GET /saludo/{nombre}` | `{"saludo":"hola, <nombre>"}` |
| `POST /eco` | el JSON recibido; si no es JSON, 400 |

Escucha en el puerto 8080, o en el de `PUERTO` si es un número de 1 a 65535; cualquier otro valor
vuelve a 8080. Por omisión solo atiende en `127.0.0.1`, la propia máquina. Para atender en todas
las interfaces —otra máquina, un contenedor—, cambiar la línea por `.direccion("0.0.0.0")`.

## Arrancarlo

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
./build/hola
curl http://127.0.0.1:8080/saludo/ana
ctest --test-dir build
```

Lo mismo en PowerShell, que hoy sirve con `pwsh` en Linux y macOS, y en Windows servirá cuando
llegue el soporte (hasta entonces, en Windows, las órdenes de arriba dentro de WSL):

```powershell
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release; cmake --build build -j
$env:PUERTO = 9000; ./build/hola
curl.exe http://127.0.0.1:9000/saludo/ana
```

`ctest` corre [`pruebas/punta_a_punta.cpp`](pruebas/punta_a_punta.cpp): arranca `hola` en un puerto
libre de 127.0.0.1 y comprueba las tres rutas.

Dentro de este repositorio, para probar contra el código de aquí al lado y no contra GitHub:

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DFETCHCONTENT_SOURCE_DIR_CERO=../..
```

## Memoria

En reposo, recién arrancado y sin ninguna petición: **1 632 KiB** de memoria residente, igual en
tres arranques seguidos. Medido el 2026-11-11 en macOS 26 sobre arm64 (Apple Silicon), con Apple
Clang y `-DCMAKE_BUILD_TYPE=Release`:

```bash
PUERTO=18500 ./build/hola >/dev/null & P=$!; sleep 1; ps -o rss= -p $P; kill $P
```
