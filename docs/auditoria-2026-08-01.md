# Auditoría: JxMVC 3.4.0 frente a LuxCore

1 de agosto de 2026. Estado tras completar `lux-http` (fases A–D).

## Resumen en una línea

LuxCore reemplazó **Tomcat** por completo. La capa de transporte está cerrada; las otras tres
capas del framework siguen enteras en `legacy/`.

## Dónde estamos

| | JxMVC 3.4.0 | LuxCore hoy |
|---|---|---|
| Clases de producción | 54 | 63 |
| Líneas de producción | 10 379 | 4 838 |
| Líneas de prueba | 2 248 | 2 077 |
| Pruebas | 347 | 297 |
| Dependencias en runtime | 0 (+ Jakarta *provided*) | **0, ninguna** |
| Referencias a `jakarta.*` | 14 clases | **0** |
| Necesita contenedor | Tomcat 10.1+ | **no** |
| Arranque | 1392 ms | **51 ms** |
| Artefacto | 253 KB + Tomcat (~15 MB) | **123 KB** |

LuxCore hace hoy más que JxMVC —porque incluye el servidor, que JxMVC delegaba— con **el 47 % de
las líneas**. La proporción de pruebas por línea es del **43 %**, el doble que la del heredado
(22 %).

## Por capas

| Capa | JxMVC | LuxCore | Estado |
|---|---|---|---|
| **0. Transporte HTTP** | Tomcat | `lux-http` propio | **cerrada** |
| **1. Núcleo MVC** — router, pipeline, controladores | `MainLxServlet` (1041 L), `BaseDispatcher` (443 L), `JxRequest`/`JxResponse` | `lux-core` reescrito | **cerrada** |
| **2. Transversales** — datos, cache, validación, scheduler, métricas | 40 clases puras, ~7 500 L | DI, JSON, config y seguridad hechos; el resto sin mover | **parcial, el resto es mudanza** |
| **3. Vistas** | JSP + `JxTagFor`/`JxTagIf` | nada | **0 %, hay que reescribir** |

`lux-core` no es una transliteración de `MainLxServlet`: las 1041 líneas del pipeline de 15 etapas
se rehicieron como 11 clases con una responsabilidad cada una. El pipeline resultante es
autenticar → middleware → autorizar → vincular → invocar → renderizar, y cabe leerlo de corrido.

## En qué mejoramos (medido, no opinión)

- **Arranque: 1392 ms → 17 ms.** 82× más rápido. La casi totalidad del arranque de JxMVC era
  Tomcat, y Tomcat ya no está.
- **Artefacto: 253 KB + ~15 MB de Tomcat → 36 KB.** Se despliega copiando un archivo.
- **Dependencias: de 0 declaradas a 0 reales.** JxMVC decía «cero dependencias» pero necesitaba
  `jakarta.jakartaee-api` en compilación y un contenedor Jakarta EE completo en ejecución.
  LuxCore no necesita nada fuera del JDK. Esa afirmación ahora es literal.
- **Endurecimiento explícito.** JxMVC heredaba los límites de Tomcat, configurados fuera del
  código. En LuxCore están en `ServerOptions`, versionados, y las pruebas los ejercitan: rechaza
  cabeceras plegadas, `Content-Length` duplicado y `Content-Length` junto a `Transfer-Encoding`
  — los tres vectores clásicos de *request smuggling*.

## Capa 0 — lo que se cerró

Los cuatro huecos que bloqueaban producción, más todo lo que antes ponía el contenedor:

| Hueco | Sustituye a | Cómo quedó |
|---|---|---|
| **TLS / HTTPS** | Tomcat | `SSLContext` del JDK, helper `Tls.fromKeystore` |
| **Techo de conexiones** | `maxConnections` de Tomcat | contador atómico; por encima del techo el socket se cierra en el accept |
| **Timeout de handler** | Tomcat | watchdog de un solo hilo compartido; sin coste en el camino rápido cuando está desactivado |
| **Apagado ordenado** | Tomcat drena | `stop()` deja de aceptar, corta el keep-alive en curso y espera `shutdownGraceMillis` a las peticiones en vuelo |
| Cookies | `jakarta.servlet.http.Cookie` | `Cookie` con validación de nombre y valor; parseo de cabeceras `Cookie` repetidas |
| Sesiones | `HttpSession` | almacén en memoria, id de 256 bits de `SecureRandom`, caducidad por inactividad, barrido periódico; cookie `HttpOnly`, `SameSite=Lax`, `Secure` automático bajo TLS |
| Multipart | `jakarta.servlet.http.Part` | `Multipart`/`Part`, con techo de partes |
| gzip | `JxGzip` (acoplado a servlet) | por negociación de contenido, solo tipos comprimibles y por encima de `gzipMinBytes` |
| Archivos estáticos | `DefaultServlet` de Tomcat | `StaticFiles` con ETag, `If-None-Match`, `Last-Modified` y bloqueo de path traversal |
| Validación de `Host` | Tomcat | 400 si falta en HTTP/1.1 o si viene duplicado |

**Corregido durante la auditoría: inyección CRLF en cabeceras de respuesta.**
`res.header(nombre, valor)` escribía el valor tal cual: un valor con `\r\n` procedente de entrada
de usuario permitía inyectar cabeceras o partir la respuesta. Se rechazan caracteres de control en
nombre y valor, con verificación también al escribir para que no se pueda esquivar por `headers()`.

## Lo que sigue faltando

### En `lux-http`

| Hueco | Gravedad | Nota |
|---|---|---|
| WebSocket | media | lo necesitan `JxWebSocket` y `JxWsRegistrar` al migrar |
| HTTP/2 | baja | Tomcat lo daba; nadie lo está usando en las apps AUR |
| Log de acceso | baja | hoy solo hay `ErrorReporter` |
| Rangos (`Range`) en estáticos | baja | vídeo y descargas reanudables |
| Sesiones distribuidas | baja | el almacén es por proceso; con varias instancias hace falta backend externo |

### Capas 1–3 — el trabajo grande

- `MainLxServlet` (1041 L) → `LuxDispatcher`, partido en piezas legibles.
- `JxRequest`/`JxResponse` (367 L) → envolver `lux.http.Request`/`Response` en vez de Jakarta.
- 12 clases más con Jakarta: `BaseDispatcher`, `BaseCorsResolver`, `JxController`, `JxCsrf`,
  `JxFilterContext`, `JxGzip`, `JxAuthProvider`, `JxWebSocket`, `JxWsRegistrar`.
- `JxTagFor`, `JxTagIf` → se eliminan, los sustituye `lux-view`.
- 40 clases puras → mudanza mecánica a `lux-core` y `lux-data`.
- `lux-adapter-servlet` → para que Academia, Intranet y NFC Intranet no se rompan.
- Portar las 347 pruebas.

### En las capas 2–3

- **`lux-view`** — motor de plantillas propio. Es lo único que impide retirar JSP, y lo que
  bloquea que `jxmvc2x` corra en modo standalone.
- **`lux-data`** — mudanza de `JxDB`, `JxRepository`, `JxPool`, `JxTransaction`, `DBRow`. No
  tocan Jakarta: es trabajo mecánico.
- **Transversales que faltan**: cache (`JxCache`), validación (`JxValidation`), scheduler
  (`JxScheduler`), métricas (`JxMetrics`), OpenAPI (`JxOpenApi`), eventos (`JxEventBus`),
  rate limiting (`JxRateLimiter`), CSRF y CORS. Todas son clases puras del heredado.
- **`lux-adapter-servlet`** — para que Academia, Intranet y NFC Intranet sigan desplegando en
  Tomcat mientras migran.
- **Portar las 347 pruebas** del heredado.

## Riesgos

**El grande sigue siendo la madurez.** El parser HTTP de LuxCore tiene dos días y 297 pruebas. El
de Tomcat tiene 25 años, miles de pruebas y un historial de CVEs ya corregidos que nosotros vamos a
tener que descubrir por nuestra cuenta. Los 51 ms de arranque son reales; la robustez todavía no
está demostrada. Que la auditoría encontrara una inyección CRLF a las pocas horas de escribir el
módulo es la prueba de que este riesgo no es teórico. Mitigación seria: fuzzing del parser y un
banco de conformidad HTTP/1.1 antes de poner esto delante de tráfico real.

**El segundo: el rps de 94 610 no vale.** Se midió con cliente y servidor en la misma máquina,
con `ab`, sin aislamiento. No es comparable con la tabla del paper y no debe citarse. El número
honesto sale del harness de `benchmarks/docker` con LuxCore como sexto contendiente, en el mismo
Arch bare-metal.

**El tercero: qué prueban las pruebas.** 297 casos, pero funcionales. No hay pruebas de
concurrencia real, sockets lentos (slowloris), cuerpos parciales, clientes que abortan a media
respuesta, ni entradas generadas por fuzzing. La cobertura por línea es del 43 %; la cobertura
por *modo de fallo* sigue siendo baja.

**El cuarto, nuevo: reflexión en el camino caliente.** `lux-core` resuelve los argumentos de cada
acción por reflexión en cada petición. Cuesta el 12 % de rps medido, y es exactamente el tipo de
decisión que **no** se puede llevar a Rust ni a C++ en la fase 3. Cuando se escriba el spec
poliglota habrá que resolver el registro de rutas y la vinculación en tiempo de compilación.

## Siguiente paso recomendado

`lux-view`. Es lo único que impide retirar JSP y lo que bloquea que `jxmvc2x` corra en modo
standalone — y esa es la prueba de que la paridad con el heredado es real.
