# Cero en Rust

Segunda implementación del contrato de [`spec/`](../spec). No es una traducción del código Java:
la referencia son los requisitos numerados, y el juez son los mismos vectores de conformidad, que
son bytes sobre un socket y no saben en qué lenguaje está escrito quien responde.

**Estado: hito 2.** El 1 pasaba los 23 vectores de RFC 9112 y 9110 y resolvía rutas según
`RUT-001`–`RUT-011`. El 2 añade las sesiones: los trece requisitos de `spec/sesiones.md`, con una
prueba por requisito que lo cita. HTTP/2, seguridad transversal y observabilidad vienen después.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores |
| 2 | Sesiones | 13 de 13 requisitos · 13 pruebas |
| 3 | Seguridad transversal | 28 de 28 requisitos · 18 pruebas |
| 4 | Observabilidad | 23 de 23 requisitos · 16 pruebas |

**101 de los 163 requisitos del contrato**, con 47 pruebas que citan cada una el suyo. Falta
HTTP/2, que son los 39 restantes.

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

## Segundo hallazgo: el lenguaje decide si un requisito se puede incumplir por descuido

`SES-011` dice que consultar la cookie pendiente **no es una lectura pura**: marca la cookie como
emitida, así que repetirla la pierde. En Java eso es una nota en el contrato y una disciplina que
hay que recordar — y no se recordó: Cero 0.6.0 tenía dos caminos de salida, uno por versión del
protocolo, y solo uno preguntaba. Sobre HTTP/2 no había sesión, y sin sesión no había token CSRF.

En Rust el método toma `&mut self`. Dos caminos de salida **no pueden consultarlo los dos** sin
que el compilador lo señale. El requisito es el mismo y el comportamiento exigido es el mismo; lo
que cambia es que en un lenguaje se puede incumplir por descuido y en el otro no.

Eso no es una virtud de Rust que haya que celebrar: es un dato sobre qué parte del contrato
depende de la disciplina del programador y qué parte puede delegarse al tipo. Un contrato
poliglota debería decir cuáles de sus requisitos son de esa clase, y hoy no lo dice.

**El hito 3 lo repitió, y con una consecuencia incómoda.** `SEG-022` dice que la cuota del
limitador no puede depender de la ruta. En Java eso es comprobable, porque la función recibía la
ruta y había que verificar que no la usara. Aquí no la recibe: **el incumplimiento no se puede
escribir**. Se intentó reintroducir el fallo para ver si la prueba lo cazaba, y no lo cazó porque
no había fallo que cazar.

Eso deja la prueba vacía, y una prueba que no puede fallar es peor que ninguna: ocupa sitio y da
confianza que no ha ganado. Se sustituyó por otra que sí verifica algo —que el mapa de cuentas no
crece con las peticiones— y el requisito quedó anotado como cumplido por la forma del tipo.

Con dos casos ya se ve el patrón, y es material del artículo 6: **el contrato tiene requisitos de
dos clases**, los que hay que probar y los que se pueden hacer irrepresentables. Cuál es cuál no
lo decide el requisito: lo decide el lenguaje.

## Tercer hallazgo: «lanzar» no significa lo mismo en los dos lenguajes

`OBS-005` dice que una comprobación de salud **que lanza** debe dar 503 y no 500: lanzar es una
forma de fallar, no un fallo del endpoint. En Java eso es atrapar una excepción, que es el
mecanismo normal de error del lenguaje.

En Rust el error normal es un valor —`Result`—, y lo que corresponde a «lanzar» es `panic!`, que
es una condición excepcional y no un modo de error corriente. Atraparlo existe
(`catch_unwind`) pero es raro y algunas configuraciones lo desactivan.

El requisito se cumple y el comportamiento observado es el mismo, pero **el requisito estaba
escrito con el vocabulario de un lenguaje**. «Que lanza» no es neutral: en Rust habría que decir
«una comprobación que falla de forma no prevista». Es una contaminación más leve que las dos
anteriores —no cambia el diseño, solo la redacción— y por eso es fácil que se cuele.

## Lo que este hito **no** hace

- No hay TLS ni WebSocket.
- No hay HTTP/2, que es el bloque que queda.
- Las sesiones viven en memoria: `SES-012` —almacén compartido entre instancias— no está.
- No hay API de aplicación estable. Lo que hoy se llame `Servidor` puede llamarse otra cosa
  mañana: el contrato es el comportamiento, no los nombres.
