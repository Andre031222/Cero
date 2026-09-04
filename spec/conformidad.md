# Conformidad — HTTP/1.1

Primer bloque del contrato. Cubre lo que entra por el socket y lo que un servidor debe rechazar,
que es la parte donde una implementación descuidada se convierte en un agujero.

**Origen:** cada requisito de aquí sale de un vector que ya corre en
`java/cero-http/src/test/java/cero/http/ConformidadTests.java`. Ninguno está inventado: el orden y
la redacción se han pasado a forma normativa, pero lo que exigen es exactamente lo que la
implementación en Java hace hoy y verifica en cada compilación.

**Cómo leerlo.** `DEBE` y `NO DEBE` en el sentido del RFC 2119. La columna «RFC» cita el apartado
que lo obliga. Una implementación conforme pasa los 23.

## Forma de la petición

| # | Requisito | RFC |
|---|---|---|
| `HTTP-001` | El destino en *origin-form* (`/ruta?consulta`) DEBE aceptarse. | 9112 §3 |
| `HTTP-002` | El destino en *absolute-form* (`http://host/ruta`) DEBE aceptarse en una petición normal, no solo hacia un proxy. | 9112 §3.2.2 |
| `HTTP-003` | El destino en *asterisk-form* (`*`) DEBE aceptarse para `OPTIONS`. | 9112 §3.2.4 |
| `HTTP-004` | Una línea de petición sin versión NO DEBE atenderse. | 9112 §3 |
| `HTTP-005` | Una versión de protocolo que no existe NO DEBE atenderse. | 9112 §2.3 |
| `HTTP-006` | Un método desconocido DEBE responderse como no implementado, no como error de sintaxis. | 9110 §9.1 |
| `HTTP-007` | Los métodos son sensibles a mayúsculas: `get` NO DEBE tratarse como `GET`. | 9112 §3 |
| `HTTP-023` | Una línea de petición que supera el límite configurado NO DEBE atenderse. | 9112 §3 |

## Cabeceras

| # | Requisito | RFC |
|---|---|---|
| `HTTP-008` | Un espacio entre el nombre de la cabecera y los dos puntos NO DEBE admitirse. | 9112 §5.1 |
| `HTTP-009` | Una cabecera plegada en varias líneas NO DEBE admitirse. | 9112 §5.2 |
| `HTTP-010` | Un nombre de cabecera con caracteres fuera del *token* NO DEBE admitirse. | 9112 §5 |
| `HTTP-011` | Un valor de cabecera con un byte nulo NO DEBE admitirse. | 9110 §5.5 |
| `HTTP-012` | El espacio en blanco alrededor del valor DEBE ignorarse, no formar parte del valor. | 9112 §5 |

Los tres primeros son la misma familia: cada uno es una forma de que dos intermediarios lean la
misma petición de dos maneras distintas. De ahí que se rechacen en vez de normalizarse.

## Host

| # | Requisito | RFC |
|---|---|---|
| `HTTP-013` | Una petición HTTP/1.1 sin `Host` NO DEBE atenderse. | 9112 §3.2 |
| `HTTP-014` | Una petición con `Host` duplicado NO DEBE atenderse. | 9112 §3.2 |
| `HTTP-015` | Una petición HTTP/1.0 sin `Host` DEBE atenderse. | 9112 §3.2 |

## Longitud del cuerpo

Aquí es donde vive el *request smuggling*, y por eso los cinco son rechazos y no correcciones.

| # | Requisito | RFC |
|---|---|---|
| `HTTP-016` | `Content-Length` y `Transfer-Encoding` en la misma petición NO DEBEN admitirse. | 9112 §6.3 |
| `HTTP-017` | Dos `Content-Length` con valores distintos NO DEBEN admitirse. | 9112 §6.3 |
| `HTTP-018` | Un `Content-Length` negativo NO DEBE admitirse. | 9112 §6.2 |
| `HTTP-019` | Un `Content-Length` que no es un número NO DEBE admitirse. | 9112 §6.2 |

## Codificación por trozos

| # | Requisito | RFC |
|---|---|---|
| `HTTP-020` | `chunked` DEBE ser la última codificación de la lista; en cualquier otra posición NO DEBE admitirse. | 9112 §7.1 |
| `HTTP-021` | Un tamaño de trozo que no es hexadecimal NO DEBE admitirse. | 9112 §7.1 |
| `HTTP-022` | Una petición `chunked` bien formada DEBE atenderse. | 9112 §7.1 |

## Lo que falta en este bloque

Se escribe aquí para que no se confunda «no está en la lista» con «está cubierto»:

- **`Transfer-Encoding` desconocido.** Qué hacer con una codificación que no se implementa.
- **Peticiones canalizadas.** El orden de las respuestas está probado, pero no especificado.
- **`Expect: 100-continue`.** Implementado y probado; sin numerar todavía.
- **Límites de tamaño.** `HTTP-023` fija que hay un límite, no cuál. Un contrato entre
  implementaciones necesita un mínimo obligatorio.
- **Códigos concretos.** Los vectores comprueban que se rechaza; el contrato no fija todavía si
  cada rechazo es 400, 501 o 505. Fijarlo es el siguiente paso, y hace falta antes de que una
  segunda implementación intente pasar la lista.
