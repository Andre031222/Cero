# Conformidad — sesiones y cookies

Tercer bloque del contrato. Cubre cómo nace una sesión, cómo viaja su identificador y qué debe
seguir siendo cierto cuando cambia el protocolo por debajo.

**Origen:** cada requisito sale de una comprobación que ya corre en
`java/cero-http/src/test/java/cero/http/SessionTests.java`. Ninguno está inventado.

**Cómo leerlo.** `DEBE` y `NO DEBE` en el sentido del RFC 2119.

## Por qué este bloque existe

Cero 0.6.0 emitía la cookie de sesión sobre HTTP/1.1 y **no** sobre HTTP/2. Las dos salidas eran
implementaciones hermanas de la misma interfaz y solo una preguntaba por la cookie pendiente, así
que sobre h2 ningún cliente podía iniciar sesión: sin sesión no hay token CSRF, y sin token CSRF
toda escritura respondía 403 apuntando al sitio equivocado.

Ninguna de las 430 comprobaciones del módulo lo vio, porque todas entraban por HTTP/1.1. No fue
un descuido de escritura: fue un camino de salida entero sin ejercitar. De ahí el requisito
`SES-010`, que es el que convierte «se nos olvidó» en «no conforme».

## Ciclo de vida

| # | Requisito | Fuente |
|---|---|---|
| `SES-001` | Una petición sin cookie de sesión NO DEBE recuperar ninguna sesión previa. | `overHttp` |
| `SES-002` | El identificador de sesión DEBE tener al menos 40 caracteres y provenir de una fuente aleatoria apta para criptografía. | `overHttp` |
| `SES-003` | Dos sesiones distintas NO DEBEN compartir identificador. | `expiry` |
| `SES-004` | Invalidar una sesión DEBE dejarla inutilizable: leerla o escribirla DEBE fallar, no crearla de nuevo en silencio. | `expiry` |
| `SES-005` | Rotar el identificador DEBE cambiarlo conservando los atributos, y DEBE obligar a reemitir la cookie exactamente una vez. | `rotacion` |
| `SES-006` | Una sesión invalidada NO DEBE poder rotarse. | `rotacion` |
| `SES-007` | Dos peticiones simultáneas sobre la misma sesión NO DEBEN perder escrituras la una de la otra. | `sinPisarse` |

## Emisión de la cookie

| # | Requisito | Fuente |
|---|---|---|
| `SES-008` | La respuesta que crea una sesión DEBE llevar la cookie; las siguientes NO DEBEN reemitirla. | `overHttp` |
| `SES-009` | La cookie de sesión DEBE ser `HttpOnly` y `SameSite=Lax`, y DEBE ser `Secure` cuando y solo cuando la conexión es segura. | `overHttp` |
| `SES-010` | La cookie de sesión DEBE emitirse **sea cual sea la versión del protocolo**. Una sesión abierta sobre HTTP/2 DEBE seguir reconociéndose en la petición siguiente, igual que sobre HTTP/1.1. | `sobreHttp2` |
| `SES-011` | La consulta de la cookie pendiente DEBE ocurrir una sola vez por respuesta: marca la cookie como emitida, así que no es una lectura pura y repetirla la pierde. | `sobreHttp2` |

## Almacenamiento

| # | Requisito | Fuente |
|---|---|---|
| `SES-012` | Con un almacén compartido, una sesión abierta en una instancia DEBE reconocerse en otra. | `almacenCompartido` |
| `SES-013` | El nombre de la tabla o del espacio de claves DEBE poder configurarse: una implementación NO DEBE exigir el nombre por omisión. | `JdbcSessions.of(...)` |

## Nota para quien porte Cero a otro lenguaje

`SES-010` y `SES-011` juntos son el requisito que más fácil se incumple, y la forma de
incumplirlo es siempre la misma: tener más de un camino de salida y poner la emisión de la cookie
en uno solo. El sitio correcto es el punto por donde pasan todos —en la implementación en Java,
el que construye el bloque de cabeceras— y no el que escribe la respuesta, que hay uno por
protocolo.

La prueba que lo verifica no puede entrar por HTTP/1.1. Tiene que hablar h2 de verdad.
