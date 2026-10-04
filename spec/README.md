# spec — el contrato de Cero

**Estado: 0.2. Manda en lo que cubre.** 173 requisitos numerados, y dos implementaciones los
pasan todos. En las áreas que este directorio todavía no describe —forma de la petición y la
respuesta, autenticación, trazado— la referencia sigue siendo `java/`, y eso está dicho más abajo.

El cambio de regla no es una declaración: es lo que pasó. La implementación en Rust se escribió
contra estos requisitos y no contra el código Java, y diez de ellos —`H2-040` a `H2-049`— nacieron
**ahí**, de los fallos que h2spec le encontró a la segunda implementación. Java los cumple, pero
nadie los había escrito. Un contrato que solo transcribe la primera implementación no puede hacer
eso.

## Para qué es

Cero quiere ser el mismo framework en Java, Rust y C++. Eso solo funciona si «el mismo» está
escrito en algún sitio que no sea código de uno de los tres. Si la referencia es la
implementación en Java, las otras dos no son implementaciones: son traducciones, y heredan hasta
sus accidentes.

El contrato describe **lo que se observa desde fuera**: qué entra por el socket, qué sale, qué
decide el ruteo, qué garantiza el ciclo de vida. No describe cómo se consigue. Un
`ConcurrentHashMap` no es parte del contrato; que dos peticiones simultáneas a la misma ruta no
se pisen la sesión, sí.

## Cómo se escribe

Tres reglas, para que el documento no se convierta en literatura:

1. **Cada requisito es comprobable desde fuera.** Si no se puede escribir una prueba que lo
   verifique hablando HTTP contra el proceso, no es un requisito: es una nota de diseño.
2. **Cada requisito tiene un número estable.** `HTTP-014` sigue siendo `HTTP-014` para siempre.
   Un requisito que se retira se marca retirado; no se reutiliza su número.
3. **Cada requisito nace de una prueba que ya existe.** No se especifica lo que no está probado.
   Esto es lo que separa un contrato de una lista de deseos, y es también por qué este borrador
   se escribe *después* del código y no antes.

## Cómo va a crecer

El contrato se extrae del banco que ya corre: **1 764 comprobaciones en 88 grupos**. Cada grupo es un
candidato a sección del contrato, y cada aserción a requisito numerado. El trabajo pendiente no
es inventar el contrato — es transcribirlo y decidir, grupo a grupo, qué parte es contrato y qué
parte es detalle de la implementación en Java.

| Área | Grupos de prueba | Estado del contrato |
|---|---|---|
| Protocolo HTTP/1.1 | 6 | [conformidad.md](conformidad.md) · **empezado** |
| Protocolo HTTP/2 | 3 | [http2.md](http2.md) · **49 requisitos + h2spec** |
| Ruteo y despacho | 5 | [ruteo.md](ruteo.md) · **37 requisitos** |
| Petición y respuesta | 9 | por escribir |
| Sesiones y cookies | 4 | [sesiones.md](sesiones.md) · **13 requisitos** |
| Seguridad transversal | 8 | [seguridad.md](seguridad.md) · **28 requisitos** |
| Plantillas | 7 | fuera del núcleo: contrato aparte |
| Datos | 9 | fuera del núcleo: contrato aparte |
| Observabilidad | 4 | [observabilidad.md](observabilidad.md) · **23 requisitos** |

## Lo que este borrador **no** dice todavía

Conviene que conste, porque un contrato incompleto que no admite estarlo hace más daño que uno
que sí:

- **Nada de la forma de la petición y la respuesta, ni de autenticación, ni de trazado
  distribuido.** Son los bloques que siguen, y hasta que estén escritos la referencia de esas
  áreas es `java/`.
- **Nada de cómo se registran las rutas ni cómo se declaran las dependencias.** El bloque de
  ruteo especifica qué resuelve el router, no cómo se le dice qué tiene que resolver: eso es
  donde Java mete reflexión y donde Rust no podrá, así que es decisión de cada implementación.
- **Nada de la API en el lenguaje.** Que en Java se llame `Cero.app()` y en Rust se llame otra
  cosa es correcto. El contrato es el comportamiento, no los nombres.
- **Nada de rendimiento.** Los números viven en `benchmarks/` y se miden; no se prometen.

## Relación con las implementaciones

```text
spec/                 el contrato — manda en lo que cubre
spec/banco/           el juez: bytes sobre un socket, sin lenguaje
java/                 primera implementación · referencia en lo que el contrato aún no cubre
rust/                 segunda implementación · 173 de 173
benchmarks/           mide, no especifica
```

## El banco ya no es una idea

[`spec/banco/`](banco/) corre los 23 vectores de HTTP/1.1 contra cualquier servidor que escuche,
en el lenguaje que sea. Y h2spec, que lo escribió otra gente, juzga HTTP/2 igual de ciego.

| Implementación | Banco HTTP/1.1 | h2spec | Requisitos |
|---|---|---|---|
| `java/` | 23 de 23 | 145 de 146 | los 173 |
| `rust/` | 23 de 23 | 145 de 146 | los 173 |

El fallo que les queda a las dos es el mismo, el `3.5.2`, y no se va a arreglar: asume un puerto
dedicado a h2c, y en uno compartido con HTTP/1.1 pide la respuesta equivocada.

Rust pasó 22 de los 23 vectores a la primera. El que falló —`Content-Length` y `Transfer-Encoding`
juntos, RFC 9112 §6.3— es una precondición de contrabando de peticiones, y lo encontró el contrato
el mismo día en que la implementación nació. Eso es exactamente para lo que existe este directorio.

## Qué manda cuando hay discrepancia

**En lo que el contrato cubre, manda el contrato**, y una implementación que no coincida está mal.
Era al revés hasta la 0.2, y se dijo que el día en que se invirtiera se diría aquí: es este.

En lo que el contrato **no** cubre sigue mandando `java/`, y no es una concesión: es la regla 3 de
este documento. No se especifica lo que no está probado, así que un área sin requisitos no tiene
contrato que ganar.
