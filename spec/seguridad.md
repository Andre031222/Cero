# Conformidad — seguridad transversal

Quinto bloque del contrato. Cubre lo que el framework hace por la aplicación **sin que la
aplicación lo pida**: cabeceras de seguridad, CORS, CSRF, límite de peticiones y saneado.

**Origen:** cada requisito sale de una comprobación que ya corre en
`java/cero-core/src/test/java/cero/core/GuardTests.java`. La columna «fuente» nombra el método.
Ninguno está inventado.

**Cómo leerlo.** `DEBE` y `NO DEBE` en el sentido del RFC 2119.

## Por qué este bloque es contrato y no configuración

Todo lo de aquí se puede apagar, y casi todo se puede ajustar. Lo que el contrato fija no es la
política, sino **qué pasa cuando nadie ha configurado nada**. Un framework cuyo comportamiento por
defecto es inseguro traslada al programador una decisión que ese programador no sabe que está
tomando.

De ahí la forma de casi todos los requisitos: no dicen «DEBE permitir configurar X», dicen «sin
configurar nada DEBE hacer Y».

## Cabeceras de seguridad

| # | Requisito | Fuente |
|---|---|---|
| `SEG-001` | Toda respuesta DEBE llevar `X-Content-Type-Options: nosniff`. | `cabecerasDeSeguridad` |
| `SEG-002` | Toda respuesta DEBE impedir el enmarcado por defecto. | `cabecerasDeSeguridad` |
| `SEG-003` | Toda respuesta DEBE declarar una política de referente. | `cabecerasDeSeguridad` |
| `SEG-004` | Toda respuesta DEBE denegar cámara, micrófono y ubicación por defecto. | `cabecerasDeSeguridad` |
| `SEG-005` | Sin TLS NO DEBE enviarse HSTS: prometer transporte seguro sobre texto plano es una promesa que no se puede cumplir. | `cabecerasDeSeguridad` |
| `SEG-006` | Sin CSP declarada NO DEBE inventarse una. Una política adivinada rompe la aplicación y enseña a desactivarla. | `cabecerasDeSeguridad` |
| `SEG-007` | Aflojar el enmarcado o declarar una CSP DEBE ser posible sin tocar el resto. | `cabecerasDeSeguridad` |

## CORS

| # | Requisito | Fuente |
|---|---|---|
| `SEG-008` | Una petición desde un origen permitido DEBE recibir la cabecera de permiso. | `cors` |
| `SEG-009` | Toda respuesta que dependa del origen DEBE llevar `Vary: Origin`. | `cors` |
| `SEG-010` | Un origen no permitido NO DEBE recibir cabecera de permiso, pero la petición simple NO DEBE bloquearse: quien decide es el navegador. | `cors` |
| `SEG-011` | Una petición sin `Origin` NO DEBE recibir ninguna cabecera CORS. | `cors` |
| `SEG-012` | Un preflight admitido DEBE responder 204 anunciando métodos, cabeceras y tiempo de caché. | `cors` |
| `SEG-013` | Un preflight de origen ajeno DEBE responder 403. A diferencia de la petición simple, aquí el servidor sí decide. | `cors` |
| `SEG-014` | Con comodín de origen y sin credenciales DEBE responderse con comodín; con credenciales DEBE devolverse el origen concreto. | `corsConCredenciales` |

`SEG-010` y `SEG-013` juntos son la parte que más se implementa mal: una petición simple no se
bloquea porque ya llegó, y bloquearla daría una falsa sensación de protección; un preflight sí se
rechaza porque su único propósito es preguntar.

## CSRF

| # | Requisito | Fuente |
|---|---|---|
| `SEG-015` | Un método seguro DEBE pasar sin token. | `csrf` |
| `SEG-016` | Un método que escribe sin token DEBE responder 403. | `csrf` |
| `SEG-017` | La respuesta que exige token DEBE emitir uno, y DEBE existir una sesión que lo ate. | `csrf` |
| `SEG-018` | Un token válido DEBE pasar y uno erróneo DEBE responder 403. | `csrf` |
| `SEG-019` | Una ruta puede eximirse, y la exención DEBE casar por segmento completo, nunca por prefijo pelado. | `csrf` |

`SEG-019` sale de un hallazgo de auditoría: eximir `/api/publico` eximía también
`/api/publicoSECRETO`.

## Límite de peticiones

| # | Requisito | Fuente |
|---|---|---|
| `SEG-020` | Al alcanzar el límite DEBE responderse 429 con `Retry-After` y sin cupo restante. | `rateLimit` |
| `SEG-021` | Las respuestas DEBEN anunciar el límite y lo que queda. | `rateLimit` |
| `SEG-022` | La cuota NO DEBE depender de la ruta: cambiar de camino no DEBE dar cupo nuevo. | `rateLimit` |

`SEG-022` sale de un hallazgo de auditoría: con la ruta dentro de la clave, repartir la carga
entre URL inventadas multiplicaba la cuota.

**Falta un requisito aquí, y falta porque falta su prueba.** El mismo hallazgo tenía una segunda
cara: la clave crecía sin tope, así que era también agotamiento de memoria y no solo un límite
esquivable. Que la clave de cuota tenga **cardinalidad acotada** debería ser requisito, pero hoy
ninguna comprobación lo verifica —solo está probado que cambiar de ruta no da cupo nuevo—. La
regla 3 de este contrato dice que no se especifica lo que no está probado, así que aquí queda
anotado como prueba pendiente, no como requisito.

## Saneado

| # | Requisito | Fuente |
|---|---|---|
| `SEG-023` | El saneado de HTML DEBE eliminar `script`, `style`, `iframe`, los manejadores de evento y el protocolo `javascript:`. | `sanitizado` |
| `SEG-024` | El saneado DEBE conservar el marcado inocuo: uno que borra todo se desactiva. | `sanitizado` |
| `SEG-025` | El saneado a texto plano DEBE quitar toda etiqueta sin dejar rastro del contenido de `script`. | `sanitizado` |
| `SEG-026` | El saneado de nombres de archivo DEBE quitar rutas y separadores de los dos sistemas, y NO DEBE devolver nunca un nombre vacío. | `sanitizado` |

## Validación

| # | Requisito | Fuente |
|---|---|---|
| `SEG-027` | Un cuerpo válido DEBE llegar a la acción sin alterar. | `validacionEnRuta` |
| `SEG-028` | Un cuerpo inválido DEBE responder 422 —no 400— y DEBE detallar qué campo falló y por qué. | `validacionEnRuta` |

422 y no 400: el cuerpo se entendió, lo que falla es su contenido. Un cliente que distingue ambos
casos puede reintentar en uno y no en el otro.

## Lo que este bloque **no** dice

- **Qué valores concretos llevan las cabeceras.** El contrato exige que existan y qué deniegan por
  defecto, no la cadena exacta.
- **Cómo se configura nada de esto.** Es API de cada lenguaje.
- **Autenticación.** Va en su propio bloque: aquí solo está lo que corre sin que la aplicación lo
  pida.
