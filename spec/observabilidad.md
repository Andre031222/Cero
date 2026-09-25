# Conformidad — observabilidad

Sexto bloque del contrato. Cubre lo que el proceso cuenta de sí mismo: salud, log, métricas y log
de acceso.

**Origen:** cada requisito sale de una comprobación que ya corre en
`java/cero-core/src/test/java/cero/core/ObservabilidadTests.java`. La columna «fuente» nombra el
método. Ninguno está inventado.

**Cómo leerlo.** `DEBE` y `NO DEBE` en el sentido del RFC 2119.

## Por qué la salud tiene dos endpoints y no uno

Un proceso puede estar vivo y no poder atender: la base de datos no responde, un servicio del que
depende está caído. Son dos preguntas distintas y confundirlas hace daño en las dos direcciones.

Si el supervisor **reinicia** por lo que solo era una base de datos lenta, cambia un problema
pasajero por una caída. Si **nunca** reinicia, un proceso colgado se queda colgado. Por eso
`OBS-001` y `OBS-004` separan «el proceso responde» de «el proceso puede trabajar», y por eso
`OBS-003` prohíbe que la primera admita comprobaciones: si las aceptara, dejaría de medir lo que
dice medir.

## Salud

| # | Requisito | Fuente |
|---|---|---|
| `OBS-001` | El endpoint de vida DEBE responder 200 mientras el proceso responda, e informar cuánto lleva en pie. | `salud` |
| `OBS-002` | El endpoint de disponibilidad DEBE responder 200 con todas las comprobaciones en verde, y nombrarlas. | `salud` |
| `OBS-003` | Una comprobación caída DEBE llevar la disponibilidad a 503 **sin cambiar la vida**, que DEBE seguir en 200. | `salud` |
| `OBS-004` | El 503 DEBE decir cuál comprobación falló, sin ocultar las que sí van. | `salud` |
| `OBS-005` | Una comprobación que lanza DEBE dar 503, no 500: lanzar es una forma de fallar, no un fallo del endpoint. | `salud` |
| `OBS-006` | En modo público el código DEBE seguir siendo 503, pero NO DEBE revelarse el mensaje del fallo, ni el nombre de la comprobación, ni la lista. | `salud` |
| `OBS-007` | En modo público y con todo en verde DEBE responderse 200 diciendo solo que está listo. | `salud` |

`OBS-006` es la razón de que el modo público exista: un endpoint de salud es alcanzable desde
fuera y enumera la infraestructura interna a quien pregunte.

## Registro

| # | Requisito | Fuente |
|---|---|---|
| `OBS-008` | Una línea de log DEBE llevar su nivel, su origen y los valores interpolados. | `registro` |
| `OBS-009` | El nivel DEBE filtrar por debajo y dejar pasar por encima, y DEBE existir un nivel que calle todo. | `registro` |
| `OBS-010` | Un error con excepción DEBE incluir el tipo y el mensaje de la excepción. | `registro` |
| `OBS-011` | Con valores de menos DEBE conservarse el marcador; con valores de más DEBEN ignorarse. Interpolar NO DEBE lanzar nunca. | `registro` |
| `OBS-012` | Un fallo al construir la respuesta DEBE registrarse con el estado **finalmente enviado**, no con el que tenía antes de fallar. | `estadoRealAunqueFalleLaVista` |

`OBS-012` sale de un caso real: la vista reventaba después de fijar 200, el cliente recibía 500 y
el log decía 200. El log describía una respuesta que nadie recibió.

## Métricas

| # | Requisito | Fuente |
|---|---|---|
| `OBS-013` | DEBEN contarse todas las peticiones y, por separado, las que fallaron. | `metricas` |
| `OBS-014` | Las métricas DEBEN agruparse por **patrón de ruta**, no por URL. | `metricas` |
| `OBS-015` | DEBE informarse la latencia por ruta, con percentiles y no solo con la media. | `metricas` |
| `OBS-016` | Un 404 DEBE contar como error. | `metricas` |
| `OBS-017` | Las rutas declaradas ignoradas NO DEBEN contarse. | `metricas` |
| `OBS-018` | DEBE existir una exposición legible por máquina con el total y el detalle por ruta. | `metricas` |

`OBS-014` no es un detalle de formato: agrupar por URL convierte `/usuarios/{id}` en tantas series
como identificadores existan. Es la misma familia que `SEG-023` —cardinalidad sin acotar a partir
de entrada externa—, aquí contra el sistema de métricas en vez de contra la memoria.

## Log de acceso

| # | Requisito | Fuente |
|---|---|---|
| `OBS-019` | DEBE registrarse una línea por petición con verbo, ruta y estado. | `acceso` |
| `OBS-020` | La ruta registrada DEBE conservar la cadena de consulta. | `acceso` |
| `OBS-021` | Un usuario no identificado DEBE aparecer con una marca explícita, no con un hueco. | `acceso` |
| `OBS-022` | DEBEN registrarse también los errores y las excepciones no controladas. | `acceso` |
| `OBS-023` | Las rutas declaradas ignoradas NO DEBEN registrarse. | `acceso` |

## Lo que este bloque **no** dice

- **El formato de las líneas.** Ni del log ni del acceso. El contrato exige qué campos van, no
  cómo se escriben.
- **Qué exposición de métricas se usa.** `OBS-018` exige que exista una legible por máquina; cuál
  es decisión de la implementación.
- **Trazado distribuido.** Va aparte: depende de una norma externa y merece su propio bloque.
