# Cero en C++

Tercera implementación del contrato de [`spec/`](../spec). Como la de Rust, no es una traducción
del código Java: la referencia son los requisitos numerados, y el juez son los mismos vectores de
conformidad, que son bytes sobre un socket y no saben en qué lenguaje está escrito quien responde.

**Estado: hito 3.** HTTP/1.1, ruteo, sesiones y seguridad transversal. **23 de 23 vectores del
banco** y 46 pruebas propias, 70 requisitos citados.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores · `HTTP-001`–`HTTP-023`, `RUT-001`–`RUT-013` |
| 2 | Sesiones | `SES-001`–`SES-011` · 11 pruebas |
| 3 | Seguridad transversal | `SEG-001`–`SEG-026` menos el token · 15 pruebas |

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

## Lo que el lenguaje pone fácil, y lo que no

`std::expected` de C++23 es exactamente el `Result` de Rust, así que el parser se lee igual en los
dos: cada rechazo cita el requisito que lo exige y viaja con el estado que el RFC le asigna, en vez
de decidirse en el sitio de la llamada.

Lo que no pone fácil es el corredor de pruebas: no hay ninguno en la biblioteca estándar. Catch2 o
GoogleTest serían la primera dependencia del proyecto, así que hay uno propio en
[`pruebas/prueba.hpp`](pruebas/prueba.hpp) — cuarenta líneas, y Java tiene el suyo por este mismo
motivo.

## Lo que falta

Los hitos 4 a 12, en el mismo orden en que se hicieron en Rust, que es el orden en que el contrato
se puede comprobar: observabilidad, el framework montado, JSON y
estáticos, HTTP/2 por capas, el contenedor de dependencias y las sesiones en tabla.
