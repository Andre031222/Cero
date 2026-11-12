# Empezar en C++

La misma API pequeña que en los otros lenguajes, con Cero traído por `FetchContent`. Hace falta
CMake 3.28 y un compilador con C++23 (Clang 17 o GCC 13 en adelante). No hay nada más que
instalar: Cero no tiene dependencias.

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

## El ejemplo

[`main.cpp`](main.cpp), una ruta por línea:

```cpp
return Servidor{}
    .get("/", [](const Contexto&) { return Json::objeto({{"hola", "mundo"}}); })
    .get("/saludo/{nombre}", [](const Contexto& c) {
        return Json::objeto({{"saludo", "hola, " + string{*c.variable("nombre")}}});
    })
    .post("/eco", [](const Contexto& c) { return c.cuerpo_json(); })
    .direccion("127.0.0.1")
    .escuchar(puerto ? stoi(puerto) : 8080);
```

| Petición | Respuesta |
|---|---|
| `GET /` | `{"hola":"mundo"}` |
| `GET /saludo/{nombre}` | `{"saludo":"hola, <nombre>"}` |
| `POST /eco` | el JSON recibido; si no es JSON, 400 |

Escucha en `127.0.0.1`, en el puerto 8080 o en el de `PUERTO`. Para abrirlo a la red,
`.direccion("0.0.0.0")`.

## Arrancarlo

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
./build/hola
curl http://127.0.0.1:8080/saludo/ana
ctest --test-dir build
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
