# Conformidad — ruteo y despacho

Cuarto bloque del contrato. Cubre cómo se decide qué código atiende una petición, qué se le pasa
y qué sale de vuelta.

**Origen:** cada requisito sale de una comprobación que ya corre en
`java/cero-core/src/test/java/cero/core/`, en `RouterTests.java`, `DispatcherTests.java` y
`RegistryTests.java`. La columna «fuente» nombra el método o el archivo que lo comprueba.
Ninguno está inventado.

**Cómo leerlo.** `DEBE` y `NO DEBE` en el sentido del RFC 2119.

## Por qué este bloque es el más difícil de escribir

Los tres bloques anteriores describen protocolo: HTTP existe fuera de Cero y el contrato solo
tenía que decir qué parte se cumple. Este no. Aquí el contrato **inventa**, y por eso es donde el
lenguaje de partida contamina sin que se note.

Java registra rutas con anotaciones leídas por reflexión en tiempo de ejecución. Ni Rust ni C++
tienen eso. Si el contrato dijera «las rutas se declaran anotando métodos», estaría exportando
una decisión de Java disfrazada de requisito. Por eso lo que sigue habla de **qué resuelve el
router**, no de cómo se le dice qué rutas tiene: el registro es responsabilidad de la
implementación y cada lenguaje lo resolverá a su manera —anotaciones, macros, registro explícito,
generación en compilación—.

La prueba de que el contrato está bien escrito es que se pueda cumplir sin reflexión. Cuando
exista la implementación en Rust lo sabremos; hasta entonces es una hipótesis, y conviene
decirlo.

## Patrones de ruta

| # | Requisito | Fuente |
|---|---|---|
| `RUT-001` | Un patrón DEBE casar un camino con el mismo número de segmentos y NO DEBE casar uno con más ni con menos. | `patrones` |
| `RUT-002` | Un segmento variable DEBE capturar el texto del segmento correspondiente y ofrecerlo por su nombre. | `patrones` |
| `RUT-003` | Un patrón DEBE admitir varias variables en un mismo camino, cada una recuperable por su nombre. | `patrones` |
| `RUT-004` | Una barra final DEBE normalizarse: `/a/7` y `/a/7/` casan igual. | `patrones` |
| `RUT-005` | Un comodín final DEBE capturar el resto del camino, sea uno o varios segmentos. | `patrones` |
| `RUT-006` | Un comodín que no esté al final DEBE rechazarse al construir el patrón, no al resolver. | `patrones` |
| `RUT-007` | Un patrón mal formado —una variable sin cerrar— DEBE rechazarse al construirlo. | `patrones` |

`RUT-006` y `RUT-007` dicen **cuándo** falla, no solo que falla. Un patrón inválido detectado al
arrancar es un error del programador; detectado al resolver es un 500 en producción.

## Resolución

| # | Requisito | Fuente |
|---|---|---|
| `RUT-008` | Ante dos patrones que casan, el literal DEBE ganar al variable: `/usuarios/nuevo` no lo atiende `/usuarios/{id}`. | `prioridad` |
| `RUT-009` | Un camino sin ruta DEBE distinguirse de un camino con ruta pero verbo no admitido. Son 404 y 405, y confundirlos oculta un error de cliente. | `errores` |
| `RUT-010` | Ante un 405 la implementación DEBE poder enumerar los verbos admitidos para ese camino. | `errores` |
| `RUT-011` | `HEAD` DEBE resolverse contra la ruta `GET` del mismo camino. | `errores` |

## Despacho

| # | Requisito | Fuente |
|---|---|---|
| `RUT-012` | Una ruta inexistente DEBE responder 404. | `enrutado` |
| `RUT-013` | Un verbo no admitido DEBE responder 405 **con la cabecera `Allow`**. | `enrutado` |
| `RUT-014` | Una variable de ruta DEBE convertirse al tipo que la acción declara. | `binding` |
| `RUT-015` | Una variable que no convierte DEBE responder 400, no 500: el cliente mandó mal la petición. | `binding` |
| `RUT-016` | Un parámetro ausente con defecto declarado DEBE recibir ese defecto, y un defecto de cadena vacía DEBE distinguirse de «sin defecto». | `binding` |
| `RUT-017` | Un cuerpo JSON DEBE poder vincularse a un tipo estructurado de la acción. | `binding` |
| `RUT-018` | Una cabecera ausente vinculada a un parámetro DEBE quedar sin valor, no fallar. | `binding` |

`RUT-016` parece un detalle y no lo es: si «sin valor» y «cadena vacía» son lo mismo, una acción
no puede distinguir `?q=` de una petición sin `q`, y eso cambia el significado de una búsqueda.

## Forma de la respuesta

| # | Requisito | Fuente |
|---|---|---|
| `RUT-019` | Devolver una cadena DEBE servirse como texto plano; devolver un objeto DEBE serializarse a JSON. | `resultados` |
| `RUT-020` | Devolver nada DEBE responder 204. | `resultados` |
| `RUT-021` | Una redirección DEBE responder 302 con `Location`. | `resultados` |
| `RUT-022` | Una descarga con nombre de archivo DEBE sanear ese nombre antes de ponerlo en la cabecera. | `binarios` |
| `RUT-023` | Un cuerpo binario DEBE llegar íntegro y sin alterar un solo octeto. | `binarios` |

`RUT-022` sale de un intento real de inyectar una cookie a través del nombre de archivo.

## Errores

| # | Requisito | Fuente |
|---|---|---|
| `RUT-024` | Una excepción no controlada DEBE responder 500 y **NO DEBE** filtrar el mensaje interno al cliente. | `errores` |
| `RUT-025` | El 500 DEBE identificar la ruta que falló en su cuerpo, para que el cliente pueda informarlo. | `errores` |
| `RUT-026` | Un fallo con estado declarado DEBE conservar ese estado y su mensaje. | `errores` |
| `RUT-027` | Un manejador de error declarado DEBE poder fijar el estado y construir el cuerpo. | `errores` |

## Pipeline

| # | Requisito | Fuente |
|---|---|---|
| `RUT-028` | El middleware DEBE envolver la acción **en el orden en que se declaró**. | `middleware` |
| `RUT-029` | El middleware DEBE correr también cuando no hay ruta: un 404 pasa por el pipeline. | `middleware` |
| `RUT-030` | Un manejador por defecto DEBE poder atender lo no enrutado, y su respuesta DEBE llevar igualmente las cabeceras que puso el middleware. | `fallback` |
| `RUT-031` | Que exista un manejador por defecto NO DEBE alterar el 405: un verbo no admitido sigue siendo 405, no cae al manejador. | `fallback` |

`RUT-029` es la que más se incumple en la práctica. Un middleware de cabeceras de seguridad que
no corre en los 404 deja sin proteger justo las respuestas que más se provocan desde fuera.

## Inyección de dependencias

| # | Requisito | Fuente |
|---|---|---|
| `RUT-032` | Una dependencia DEBE poder resolverse por su tipo concreto y por un contrato que implemente. | `RegistryTests` |
| `RUT-033` | Una dependencia resuelta DEBE ser única por contenedor: dos resoluciones devuelven lo mismo. | `RegistryTests` |
| `RUT-034` | Una cadena de dependencias de cualquier profundidad DEBE resolverse, y todo lo de la cadena DEBE seguir siendo único. | `RegistryTests` |
| `RUT-035` | Un ciclo de dependencias DEBE detectarse y reportarse como tal, no colgarse ni desbordar la pila. | `RegistryTests` |
| `RUT-036` | Un tipo no registrado DEBE fallar al resolverse, no construirse en silencio. | `RegistryTests` |
| `RUT-037` | El contexto de la petición DEBE estar disponible para la acción sin declararlo como dependencia del contenedor. | `binding` |

`RUT-034` existe por un fallo propio: la resolución recursiva dentro de la caché de instancias
lanzaba `Recursive update` en cadenas hondas. Funcionaba con dos niveles y fallaba con tres.

## Lo que este bloque **no** dice

- **Cómo se registran las rutas.** Anotaciones, macros, registro explícito o generación en
  compilación: es asunto de la implementación. El contrato solo exige que el router resuelva
  según `RUT-001`–`RUT-011`.
- **Cómo se declara una dependencia.** Lo mismo.
- **Qué tipos concretos se aceptan al vincular.** `RUT-014` exige conversión al tipo declarado;
  qué tipos existen depende del lenguaje.
