# HTTP/2 — el contrato

Segundo bloque, después de [conformidad.md](conformidad.md), que cubre HTTP/1.1.

**Las tres puertas están abiertas:** conocimiento previo, `Upgrade: h2c` y ALPN sobre TLS. Esta
última es la que importa de cara a internet — ningún navegador habla h2 en claro, así que sin ALPN
el módulo entero no llegaba a un navegador nunca.

## h2spec

La suite de conformidad del ecosistema, escrita por otros. Es lo que separa «comprueba lo que se
nos ocurrió comprobar» de «comprueba lo que dice el RFC».

```
146 tests, 145 passed, 0 skipped, 1 failed     ← Java
146 tests, 145 passed, 0 skipped, 1 failed     ← Rust
```

**La primera corrida en Java dio 124 de 146.** Los 22 fallos tenían pocas causas: no había máquina
de estados de flujo, y faltaban las validaciones de forma de PRIORITY, RST_STREAM y SETTINGS.
Ninguno lo veían las pruebas propias, que es exactamente el motivo de correr una de fuera.

**La primera en Rust dio 130,** con las 131 pruebas propias en verde. Los 16 fallos salieron de seis
causas, y de ellas nacen `H2-040` a `H2-049`: el contrato no las pedía porque a nadie se le habían
ocurrido. Es el argumento de `spec/` funcionando al revés de lo previsto —se esperaba que el
contrato guiara a la implementación, y aquí fue la segunda implementación la que completó el
contrato—.

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
| Control de flujo de entrada | 300 KB de bajada y 100 KB de subida, y `curl` con 533 KB |
| Control de flujo de salida | el crédito se reparte entre hilos y se espera cuando falta |
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
| `H2-045` | WINDOW_UPDATE sobre un flujo que todavía no ha existido NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §5.1 |
| `H2-049` | PRIORITY sobre el flujo 0 NO DEBE admitirse. | PROTOCOL_ERROR | 9113 §6.3 |

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
| `H2-043` | Un `content-length` que no cuadre con el cuerpo recibido DEBE tratarse como malformado. | 9113 §8.1.2.6 |
| `H2-044` | Un flujo que se declara dependiente de sí mismo DEBE cortar ese flujo. | 9113 §5.3.1 |
| `H2-047` | Cabeceras sobre un flujo cuyo cliente ya dijo `END_STREAM` DEBEN cortar ese flujo. | 9113 §5.1 |

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

### HTTP/2 Bomb

Estos requisitos están inspirados en la familia de ataques que se publicó el 2 de junio de 2026 con
el nombre de HTTP/2 Bomb ([HAProxy](https://www.haproxy.com/blog/haproxy-cve-2026-49975-http2-bomb),
[Borecraft](https://borecraft.com/news/http2-bomb-cve-2026-49975-guide.html)). El número
CVE-2026-49975 es el de la variante de Apache httpd (`mod_http2`); aquí no se mitiga ese CVE, que
es de otro programa, sino la técnica, que junta tres cosas que por separado parecían cubiertas. Un octeto de HPACK que referencia una entrada grande de la tabla
dinámica se expande a una cabecera entera, miles de veces por bloque. Una ventana inicial a 0 deja
cada respuesta construida y sin poder salir. Y un `WINDOW_UPDATE` de un octeto de vez en cuando
reinicia los plazos de envío, así que la memoria de cada petición no se libera nunca. `H2-039`
limitaba el tamaño de lo que sale de HPACK; no el número de campos, ni lo que la respuesta retiene
mientras espera.

| # | Requisito | Código | RFC / CVE |
|---|---|---|---|
| `H2-050` | Un bloque de cabeceras que al decodificarse pasa de **256 campos** NO DEBE admitirse, y uno de 256 que quepa en la lista anunciada DEBE admitirse. Cada `cookie` partida cuenta como un campo. | GOAWAY ENHANCE_YOUR_CALM | HTTP/2 Bomb |
| `H2-051` | El servidor DEBE anunciar `SETTINGS_MAX_HEADER_LIST_SIZE`, admitir una lista decodificada que quepa en él y NO DEBE admitir una que lo pase. | GOAWAY ENHANCE_YOUR_CALM | 9113 §6.5.2 |
| `H2-052` | Un flujo que pasa el plazo configurado sin poder mandar una trama DATA completa por falta de ventana DEBE cortarse. Gotear `WINDOW_UPDATE` de un octeto NO DEBE reiniciar ese plazo. | RST_STREAM CANCEL | HTTP/2 Bomb |
| `H2-053` | El plazo de inactividad de la conexión solo DEBE reiniciarse con HEADERS, CONTINUATION o DATA, y NO DEBE correr mientras algún flujo tenga su respuesta en marcha. `PING`, `WINDOW_UPDATE`, `SETTINGS` y `PRIORITY` NO DEBEN reiniciarlo. | GOAWAY NO_ERROR | 9113 §9.1 |
| `H2-054` | Los octetos de respuesta construidos y todavía sin mandar en una conexión NO DEBEN pasar del presupuesto configurado, que por defecto son 32 MiB. Una respuesta que llega sin ninguna otra en vuelo DEBE admitirse aunque sola lo pase. | GOAWAY ENHANCE_YOUR_CALM | HTTP/2 Bomb |
| `H2-055` | Un cuerpo de petición que pasa del tope configurado, 10 MiB por defecto, DEBE responderse con 413 y NO DEBE retenerse; el flujo se cierra con RST_STREAM NO_ERROR. | 413 y RST_STREAM NO_ERROR | 9110 §15.5.14 |

Los seis se comprueban desde fuera en [`banco/ataques.py`](banco/ataques.py), con el servidor
arrancado con plazos cortos.

- **`H2-050` existe aunque esté `H2-051`.** Cada campo cuesta al menos 32 octetos en la cuenta del
  RFC, así que 16 KiB de lista dejan pasar quinientos campos vacíos, y lo caro de un campo no es su
  tamaño sino lo que el servidor hace con él después. nginx cerró la suya igual, con un tope de
  número: la directiva `max_headers`, de la 1.29.8, con 1 000 por defecto
  ([Borecraft](https://borecraft.com/news/http2-bomb-cve-2026-49975-guide.html)). Se cuenta lo que sale de HPACK, **antes** de juntar las `cookie`: juntarlas
  primero dejaría pasar diez mil trozos como una sola cabecera.
- **`H2-051` es `H2-039` con el número a la vista.** El tope que se aplica es el que se anuncia, ni
  más ni menos, y se comprueba al decodificar, campo a campo, no al terminar: una lista expandida
  que se deja crecer hasta el final ya ha ocupado la memoria que el tope quería ahorrar.
- **`H2-052` mide tramas, no octetos.** Un plazo que se reinicia con cada octeto de crédito no es un
  plazo: es lo que el goteo explota. Cada trama de hasta 16 KiB tiene que salir entera en el plazo;
  un cliente que lee de verdad, aunque lento, lo cumple, y uno que gotea un octeto cada minuto no.
- **`H2-053` mira el trabajo, no el tráfico.** Un `PING` cada pocos segundos es una trama válida que
  no pide nada; si reinicia el reloj, cualquier conexión puede quedarse para siempre. Mientras un
  flujo tenga una respuesta en marcha no hay inactividad: de esos se encarga `H2-052`.
- **`H2-054` es el lector lento.** Con la ventana abierta y el socket leído a unos cientos de octetos
  por segundo, cada flujo construye su respuesta entera y espera turno. El presupuesto cuenta lo
  construido y no mandado; cuando lo pasa, la conexión se cierra, porque no hay forma de que ese
  cliente se ponga al día. La excepción es la respuesta que llega sola: si no hay nada más en vuelo,
  el presupuesto no protege a nadie y cortarla solo rompería una descarga grande.
- **`H2-055` es el otro lado de la misma memoria.** La ventana de entrada se repone en cuanto llega
  cada DATA, así que sin tope un solo flujo puede subir lo que quiera. Se responde lo mismo que en
  HTTP/1.1 (`HTTP-026`) y se manda `RST_STREAM NO_ERROR` para que el cliente deje de enviar
  (RFC 9113 §8.1).

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
| `H2-040` | El servidor NO DEBE mandar más DATA del que quepa en la ventana del cliente, ni en el flujo ni en la conexión. | 9113 §6.9.1 |
| `H2-041` | Un WINDOW_UPDATE que suba una ventana por encima de 2^31-1 NO DEBE admitirse. | 9113 §6.9.1 |
| `H2-042` | Los ajustes que manda el cliente valen para lo que él recibe; NO DEBEN mover los topes del servidor. | 9113 §6.5.2 |
| `H2-046` | El tope de flujos concurrentes DEBE contar todo flujo vivo, incluido el que espera respuesta. | 9113 §5.1.2 |
| `H2-048` | Un error de conexión DEBE poder leerse: el cierre no puede borrar el GOAWAY que lo explica. | 9113 §9.1 |

Los tres últimos son un mismo descuido con tres caras, y salieron al portar el módulo a Rust: hay
**dos juegos de ajustes por conexión** y es fácil escribir uno. Los nuestros dicen lo que
admitimos recibir; los del cliente, lo que él admite recibir.

- Tomar su `SETTINGS_INITIAL_WINDOW_SIZE` como ventana de entrada deja la **salida sin control de
  flujo** (`H2-040`): el servidor manda todo lo que tiene en cuanto lo tiene, que va bien contra un
  cliente que lee rápido y desborda al que no.
- Y deja al cliente **subiendo por SETTINGS los topes que lo contienen** (`H2-042`). Es el más
  grave de los tres y no parece un fallo de seguridad al leerlo: con
  `SETTINGS_MAX_HEADER_LIST_SIZE` a 2^32-1 desaparece `H2-039`, y con
  `SETTINGS_MAX_CONCURRENT_STREAMS` igual, el tope de flujos en vuelo. Dos defensas anuladas por
  una trama perfectamente válida de seis octetos.

`H2-041` es el reverso de `H2-040`: quien lleva la cuenta de una ventana tiene que rechazar al que
se la desborda, o las dos puntas dejan de contar lo mismo.

`H2-046` y `H2-048` son los dos que más tiempo llevaron y los dos que no se ven leyendo el código.

- El tope de concurrencia parecía tener que descontar al flujo que ya dijo `END_STREAM` —a quien
  terminó de hablar no se le debe nada— y es justo al revés: ese es el que tiene trabajo en marcha.
  Con el descuento, el tope no llegaba a tocar nunca.
- Y un GOAWAY se puede escribir bien y perderse igual. Cerrar un socket con octetos sin leer en el
  búfer de recepción no manda un FIN, manda un RST, y un RST borra en la otra punta lo que todavía
  no había leído. El cliente veía «connection reset by peer» en vez del motivo. La cura es media
  conexión primero y vaciar lo que quede después (§9.1).

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

- **Prioridad de escritura entre flujos.** El orden lo decide el candado de salida. Funciona,
  pero no hay una política: un flujo que escribe mucho puede hacer esperar a otro que escribe
  poco.
- **h2 sobre TLS con certificado recargable.** ALPN se fija al abrir el socket de escucha; si el
  certificado se recarga, la lista de protocolos no se vuelve a mirar. Hoy no cambia nunca, así
  que no se nota — pero es una suposición sin comprobar.
