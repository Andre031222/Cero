# Cero en Rust

Segunda implementación del contrato de [`spec/`](../spec). No es una traducción del código Java:
la referencia son los requisitos numerados, y el juez son los mismos vectores de conformidad, que
son bytes sobre un socket y no saben en qué lenguaje está escrito quien responde.

**Estado: hito 1.** El objetivo de este hito es pasar los 23 vectores de RFC 9112 y 9110 y
resolver las rutas según `RUT-001`–`RUT-011`. Nada más. Sesiones, HTTP/2, seguridad transversal y
observabilidad vienen después, bloque a bloque.

```bash
cd rust && cargo test          # las pruebas de la implementación
cargo run --bin conforme 8777  # un servidor mínimo contra el que correr los vectores
```

## Sin dependencias, y lo que eso obliga a cambiar

`Cargo.toml` no declara ninguna, igual que los módulos de Java no declaran ninguna fuera del JDK.
Esa regla, que en Java era casi gratis, aquí cuesta de inmediato — y esa es exactamente la
pregunta del proyecto: **¿qué supuestos mete un lenguaje en un diseño sin que su autor se dé
cuenta?**

**Primer hallazgo, y aparece en la primera línea del servidor.** Java resuelve la concurrencia con
un hilo virtual por conexión, y los hilos virtuales están *en la plataforma*: no son una
dependencia. La biblioteca estándar de Rust no tiene nada equivalente. Tiene hilos del sistema, y
tiene `async`/`await` como sintaxis **pero sin runtime que la ejecute**: cualquier runtime es una
caja externa.

Así que «cero dependencias» en Rust fuerza **un hilo del sistema por conexión**, que es un modelo
distinto: más caro por conexión y con un tope mucho más bajo. Las opciones eran tres y ninguna
sale gratis:

| Opción | Qué cuesta |
|---|---|
| Hilo del sistema por conexión | Se mantiene la regla; el modelo de concurrencia deja de ser el mismo |
| Un runtime async externo | Se rompe la regla que define el proyecto |
| Escribir el runtime | Deja de ser un framework web y pasa a ser otra cosa |

Se toma la primera, y **el contrato no cambia**: ningún requisito de `spec/` habla de hilos. Que
eso siga siendo cierto cuando lleguen las sesiones y la concurrencia es justamente lo que hay que
comprobar, no lo que se puede suponer.

## Lo que este hito **no** hace

- No hay HTTP/2, ni TLS, ni WebSocket.
- No hay sesiones, ni seguridad transversal, ni observabilidad.
- No hay API de aplicación estable. Lo que hoy se llame `Servidor` puede llamarse otra cosa
  mañana: el contrato es el comportamiento, no los nombres.
