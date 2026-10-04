# Cero en C++

Tercera implementación del contrato de [`spec/`](../spec). Como la de Rust, no es una traducción
del código Java: la referencia son los requisitos numerados, y el juez son los mismos vectores de
conformidad, que son bytes sobre un socket y no saben en qué lenguaje está escrito quien responde.

**Estado: hito 8.** El framework usable sobre HTTP/1.1, y de HTTP/2 las tramas y HPACK. **23 de 23
vectores del banco**, **los 32 del apéndice C del RFC 7541** y 123 pruebas propias.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores · `HTTP-001`–`HTTP-023`, `RUT-001`–`RUT-013` |
| 2 | Sesiones | `SES-001`–`SES-011` · 11 pruebas |
| 3 | Seguridad transversal | `SEG-001`–`SEG-026` menos el token · 15 pruebas |
| 4 | Observabilidad | `OBS-001`–`OBS-022` menos dos del pipeline · 15 pruebas |
| 5 | El framework montado | pipeline completo · 15 pruebas |
| 6 | JSON, formularios y estáticos | lo que hacía falta para usarlo · 12 pruebas |
| 7 | HTTP/2 · capa de tramas | `H2-001`–`H2-014` y `H2-027` · 15 pruebas |
| 8 | HTTP/2 · HPACK | `H2-015`, `H2-016`, `H2-033`, `H2-039` · 20 pruebas con los vectores del RFC |

## Usarlo

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
ctest --test-dir build                       # las pruebas propias
./build/conforme 8777                        # y el servidor del banco
python3 ../spec/banco/correr.py 127.0.0.1 8777
```

## La decisión de fondo: C++ no tiene sockets

Java los trae en el JDK. Rust los trae en `std::net`. El estándar de C++ no trae ninguno, porque
el *Networking TS* se abandonó. Quedaban dos salidas: traer asio —una dependencia, y este proyecto
no admite ninguna— o llamar a POSIX. Se llama a POSIX.

Es el mismo tipo de decisión que en Rust fue no usar un runtime asíncrono: el contrato **no habla
de cómo se abre el socket**, así que se cumple igual. Lo que cambia es que aquí no hay nada
portable debajo, y eso hay que decirlo en vez de disimularlo. Como en Rust, un hilo del sistema por
conexión: Java tiene hilos virtuales en la plataforma y aquí no hay equivalente sin dependencias.

TLS, igual que en Rust: **fuera del proceso**. El argumento está en
[`rust/LEEME.md`](../rust/LEEME.md) y no cambia porque cambie el lenguaje — escribir una pila TLS
cuya corrección no se puede comprobar desde fuera sería la clase de afirmación sin medir que el
resto del proyecto se niega a hacer.

## Qué se trae de la implementación en Rust

Dos cosas aprendidas por las malas allí, aplicadas desde el primer día:

**Una prueba por requisito, en `pruebas/`.** En Rust los 35 requisitos de ruteo figuraron cubiertos
durante once hitos por estar citados en `src/`, junto al código que los implementa, que es justo
donde citarlos no demuestra nada. La cuenta solo mira `pruebas/`.

**El pipeline se puede llamar sin socket.** `Servidor::responder` toma una petición y devuelve la
respuesta. Es lo que hace probable el ruteo sin abrir un puerto, y de paso lo que permite montar
Cero dentro de otra cosa.

## Las tablas de HPACK no están escritas a mano

Se generan de los apéndices A y B del RFC con un guion, igual que en Java y en Rust. Lo que las
respalda es la **suma de Kraft** —la de dos elevado a menos cada longitud—, que en un código
prefijo completo vale exactamente 1, y hay una prueba que la calcula: una tabla de 257 filas
copiada a mano no revienta cuando se equivoca, decodifica mal y en silencio.

Y los vectores del apéndice C son la única comprobación de todo el proyecto que no se puede
escribir «de acuerdo con lo que hace el código»: los octetos vienen dados y **el estado de la tabla
dinámica después de cada paso también**. Un decodificador que acierte el resultado y deje la tabla
distinta pasaría cualquier prueba propia y fallaría en la petición siguiente.

## Las cabeceras se declaran a mano, y eso lo decidió el CI

Dos veces falló en GitHub lo que aquí compilaba: `<print>` no existe hasta GCC 14 y `std::llround`
no venía sin `<cmath>`. La causa de la segunda es que libc++ arrastra cabeceras que libstdc++ no,
así que un archivo que *parecía* completo solo lo estaba en una implementación.

Ahora **cada archivo incluye lo que usa**, sin heredarlo de otro. Es más líneas de `#include` y a
cambio el proyecto compila igual en los dos sitios, que es la mitad del argumento de no tener
dependencias: si el framework solo construye con un compilador, el compilador es la dependencia.

## Lo que el lenguaje pone fácil, y lo que no

`std::expected` de C++23 es exactamente el `Result` de Rust, así que el parser se lee igual en los
dos: cada rechazo cita el requisito que lo exige y viaja con el estado que el RFC le asigna, en vez
de decidirse en el sitio de la llamada.

Lo que no pone fácil es el corredor de pruebas: no hay ninguno en la biblioteca estándar. Catch2 o
GoogleTest serían la primera dependencia del proyecto, así que hay uno propio en
[`pruebas/prueba.hpp`](pruebas/prueba.hpp) — cuarenta líneas, y Java tiene el suyo por este mismo
motivo.

## Los dos fallos que en Rust solo aparecieron al montarlo

Aquí están comprobados desde el principio, porque ninguna prueba de módulo puede verlos.

**El CSRF tapaba el 405.** Corría antes del ruteo, así que un verbo no admitido recibía 403 en vez
de 405: la respuesta atribuía el fallo a la causa equivocada, que es lo que `RUT-009` prohíbe al
exigir distinguir 404 de 405. Ahora el ruteo va primero y el CSRF después, ya sabiendo que la
petición iba a alguna parte.

**La sesión creada dentro de la acción no emitía su cookie.** `abrir_sesion()` la creaba, pero el
punto que emite la cookie solo miraba la que había llegado **con** la petición. El cliente abría
sesión y no recibía nada. Es `SES-010` con otra cara, y por eso emitir la cookie, guardar la sesión
y anotar las métricas pasan por un único sitio.

## Lo que falta

Los hitos 9 a 12, en el mismo orden en que se hicieron en Rust, que es el orden en que el contrato
se puede comprobar: los flujos y el control de flujo de salida, el contenedor de dependencias y
las sesiones en tabla.
