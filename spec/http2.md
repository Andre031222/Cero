# HTTP/2 — el contrato

Segundo bloque, después de [conformidad.md](conformidad.md), que cubre HTTP/1.1.

**Las tres puertas están abiertas:** conocimiento previo, `Upgrade: h2c` y ALPN sobre TLS. Esta
última es la que importa de cara a internet — ningún navegador habla h2 en claro, así que sin ALPN
el módulo entero no llegaba a un navegador nunca.

## h2spec

La suite de conformidad del ecosistema, escrita por otros. Es lo que separa «comprueba lo que se
nos ocurrió comprobar» de «comprueba lo que dice el RFC».

```
146 tests, 145 passed, 0 skipped, 1 failed
```

**La primera corrida dio 124 de 146.** Los 22 fallos tenían pocas causas: no había máquina de
estados de flujo, y faltaban las validaciones de forma de PRIORITY, RST_STREAM y SETTINGS. Ninguno
lo veían las pruebas propias, que es exactamente el motivo de correr una de fuera.

**El que queda —`3.5.2`, «Sends invalid connection preface»— no aplica y no se va a arreglar.**
Manda `INVALID CONNECTION PREFACE\r\n\r\n` y espera un GOAWAY. En un puerto compartido con
HTTP/1.1 eso es una petición con un método que no existe, y la respuesta correcta es **501**, que
es la que se da. Contestar un GOAWAY binario a un cliente HTTP/1.1 sería peor que fallar el test.
El caso asume un puerto dedicado a h2c.

Corre en integración continua en cada cambio, y falla si aparece cualquier fallo que no sea ese.

## Lo que está, y cómo se comprueba

| Pieza | Cómo se verifica |
|---|---|
| HPACK — estática, dinámica y Huffman | los 32 vectores del apéndice C del RFC 7541 |
| Capa de tramas y máquina de flujos | 24 vectores propios en `Http2Tests` |
| Multiplexación | 24 peticiones de 120 ms en 129 ms sobre una conexión |
| Control de flujo, conexión y flujo | 300 KB de bajada y 100 KB de subida, y `curl` con 533 KB |
| Respuestas por `stream()` | 384 KB en tramas, sin acumular y sin `content-length` |
| CONTINUATION | 400 cabeceras que no caben en una trama |
| Trailers | se descartan, pero se decodifican para no descolocar HPACK |
| SETTINGS, PING, RST_STREAM, GOAWAY | `Http2Tests` |
| Entrada por conocimiento previo | `curl --http2-prior-knowledge` |
| Entrada por `Upgrade: h2c` | `curl --http2` y un vector propio |
| Entrada por ALPN sobre TLS | `curl --http2` sobre https y un vector propio |

Las tablas de HPACK **no están escritas a mano**: se generan del RFC y se validan con la suma de
Kraft, que en un código prefijo completo vale exactamente 1. Una tabla de 257 filas copiada a mano
no revienta cuando se equivoca — decodifica mal, en silencio.

## Requisitos

`DEBE` y `NO DEBE` en el sentido del RFC 2119. La columna «RFC» cita el apartado del 9113 que lo
obliga, o el 7541 para lo de HPACK. Continúan la numeración de `conformidad.md`.

### Errores de conexión

Rompen la conexión entera con GOAWAY, porque después de ellos no se puede seguir interpretando
nada. Cada uno lleva su código exacto: cerrar sin decir por qué deja al cliente sin saber si
reintentar.

| # | Requisito | Código | RFC |
|---|---|---|---|
| `H2-001` | Un preámbulo que no sea el preámbulo NO DEBE atenderse. | — | 9113 §3.4 |
| `H2-002` | SETTINGS sobre un flujo distinto de 0 NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.5 |
| `H2-003` | SETTINGS cuyo tamaño no sea múltiplo de seis NO DEBE admitirse. | FRAME_SIZE_ERROR | 9113 §6.5 |
| `H2-004` | DATA sobre el flujo 0 NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.1 |
| `H2-005` | HEADERS sobre el flujo 0 NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.2 |
| `H2-006` | HEADERS sobre un flujo par NO DEBE admitirse: los pares son del servidor. | PROTOCOL_ERROR | 9113 §5.1.1 |
| `H2-007` | Un identificador de flujo menor o igual que uno ya usado NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §5.1.1 |
| `H2-008` | CONTINUATION sin un HEADERS delante NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.10 |
| `H2-009` | PUSH_PROMISE de un cliente NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.6 |
| `H2-010` | PING que no mida ocho octetos NO DEBE admitirse. | FRAME_SIZE_ERROR o PROTOCOL_ERROR | 9113 §6.7 |
| `H2-011` | PING sobre un flujo NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.7 |
| `H2-012` | WINDOW_UPDATE que no mida cuatro octetos NO DEBE admitirse. | FRAME_SIZE_ERROR | 9113 §6.9 |
| `H2-013` | WINDOW_UPDATE con incremento cero sobre la conexión NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.9 |
| `H2-014` | Una trama mayor que `SETTINGS_MAX_FRAME_SIZE` NO DEBE admitirse. | FRAME_SIZE_ERROR | 9113 §4.2 |
| `H2-015` | Un índice de HPACK fuera de la tabla NO DEBE admitirse. | COMPRESSION_ERROR | 7541 §2.3.3 |
| `H2-016` | El símbolo EOS dentro de una cadena Huffman NO DEBE admitirse. | COMPRESSION_ERROR | 7541 §5.2 |
| `H2-017` | Un segundo bloque de cabeceras sin fin de flujo NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §8.1 |

### Errores de flujo

Cortan un flujo con RST_STREAM y **dejan la conexión en pie**: las demás peticiones de ese cliente
no tienen la culpa. Confundir estos con los de arriba convierte una petición mal formada en una
caída de todo lo que ese cliente tuviera en vuelo.

| # | Requisito | RFC |
|---|---|---|
| `H2-018` | Un método que el servidor no implementa DEBE cortar el flujo, no la conexión. | 9113 §8.1 |
| `H2-019` | Un nombre de campo con mayúsculas DEBE tratarse como malformado. | 9113 §8.2.1 |
| `H2-020` | Faltar `:method`, `:scheme` o `:path` DEBE tratarse como malformado. | 9113 §8.3.1 |
| `H2-021` | Un `:path` vacío DEBE tratarse como malformado. | 9113 §8.3.1 |
| `H2-022` | Un pseudo-campo repetido DEBE tratarse como malformado. | 9113 §8.3 |
| `H2-023` | Un pseudo-campo después de un campo normal DEBE tratarse como malformado. | 9113 §8.3 |
| `H2-024` | Un pseudo-campo desconocido DEBE tratarse como malformado. | 9113 §8.3 |
| `H2-025` | Una cabecera específica de conexión DEBE tratarse como malformada. | 9113 §8.2.2 |
| `H2-026` | Un `TE` con un valor distinto de `trailers` DEBE tratarse como malformado. | 9113 §8.2.2 |

### Inundaciones

No son entradas malformadas: cada trama es válida por separado, así que ningún control de
sintaxis las caza. Lo que las define es que el protocolo deja al cliente pedir trabajo sin coste
propio, y sin topes explícitos eso es una negación de servicio con una sola conexión. Se responde
con `ENHANCE_YOUR_CALM`, que distingue «te estás pasando» de «esto está mal escrito».

| # | Requisito | RFC / CVE |
|---|---|---|
| `H2-036` | Un bloque de cabeceras sin fin, repartido en CONTINUATION, NO DEBE admitirse. | CVE-2024-27316 |
| `H2-037` | Abrir y anular flujos en bucle NO DEBE poder pedir trabajo sin límite. | CVE-2023-44487 |
| `H2-038` | Las tramas de control sin abrir ningún flujo NO DEBEN admitirse sin tope. | 9113 §10.5 |
| `H2-039` | Una lista de cabeceras que se expande al descomprimirse NO DEBE admitirse. | 7541 §7.1 |

`H2-039` es el que menos se ve venir: limitar el bloque comprimido no basta, porque tres
kilobytes en el cable pueden ser trescientos al salir — basta con meter una entrada grande en la
tabla dinámica y referenciarla cien veces, a un octeto por referencia. Hay que mirar lo que sale,
y el tope se anuncia además en `SETTINGS_MAX_HEADER_LIST_SIZE` para que un cliente educado no
llegue a mandarlo.

### Comportamiento

| # | Requisito | RFC |
|---|---|---|
| `H2-027` | El servidor DEBE mandar sus SETTINGS como primera trama. | 9113 §3.4 |
| `H2-028` | Un PING sin ACK DEBE contestarse con la misma carga y el ACK puesto. | 9113 §6.7 |
| `H2-029` | Los flujos DEBEN atenderse en paralelo, no en fila. | 9113 §5 |
| `H2-030` | Un flujo anulado NO DEBE afectar a los demás de esa conexión. | 9113 §5.1 |
| `H2-031` | La ventana de un flujo nuevo DEBE ser la negociada, no la del RFC. | 9113 §6.9.2 |
| `H2-032` | Un bloque de cabeceras mayor que una trama DEBE continuar en CONTINUATION. | 9113 §6.10 |
| `H2-033` | Los nombres de campo de la respuesta DEBEN ir en minúscula. | 9113 §8.2.1 |
| `H2-034` | Las cabeceras de conexión NO DEBEN emitirse en la respuesta. | 9113 §8.2.2 |
| `H2-035` | Los trailers DEBEN decodificarse aunque se descarten, o HPACK se descoloca. | 7541 §4 |

## Lo que no va a estar, y por qué

- **PUSH_PROMISE.** En desuso; los navegadores lo retiraron. Se anuncia deshabilitado en SETTINGS,
  que es lo que manda el RFC.
- **PRIORITY.** El RFC 9113 deprecó el esquema de prioridades del 7540. Se lee y se descarta.
- **WebSocket sobre h2** (RFC 8441). `switchProtocols()` falla con un 501 explícito en vez de
  devolver un 101, que en h2 no significa nada.
- **Trailers hacia la aplicación.** Se decodifican y se descartan, igual que hace el módulo con
  HTTP/1.1. Exponerlos solo en h2 dejaría una API que existe según el protocolo por debajo, que
  es justo lo que este framework evita.

## Lo que falta

- **Prioridad de escritura entre flujos.** Con muchos flujos escribiendo a la vez, el orden lo
  decide el candado de salida. Funciona, pero no hay una política.
- **Prioridad de escritura entre flujos.** El orden lo decide el candado de salida. Funciona,
  pero no hay una política: un flujo que escribe mucho puede hacer esperar a otro que escribe
  poco.
- **h2 sobre TLS con certificado recargable.** ALPN se fija al abrir el socket de escucha; si el
  certificado se recarga, la lista de protocolos no se vuelve a mirar. Hoy no cambia nunca, así
  que no se nota — pero es una suposición sin comprobar.
