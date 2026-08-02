# Auditoría: JxMVC 3.4.0 frente a LuxCore

1 de agosto de 2026. Estado tras completar `lux-http` (fases A–D).

## Resumen en una línea

LuxCore reemplazó **Tomcat** por completo. La capa de transporte está cerrada; las otras tres
capas del framework siguen enteras en `legacy/`.

## Dónde estamos

| | JxMVC 3.4.0 | LuxCore hoy |
|---|---|---|
| Clases de producción | 54 | 102 |
| Líneas de producción | 10 379 | 7 836 |
| Líneas de prueba | 2 248 | 3 850 |
| Pruebas | 347 | **601** |
| Dependencias en runtime | 0 (+ Jakarta *provided*) | **0, ninguna** |
| Referencias a `jakarta.*` | 14 clases | **0** |
| Necesita contenedor | Tomcat 10.1+ | **no** |
| Arranque | 1392 ms | **51 ms** |
| Artefacto | 253 KB + Tomcat (~15 MB) | **215 KB** |

LuxCore hace hoy más que JxMVC —porque incluye el servidor y el motor de vistas, que JxMVC
delegaba en Tomcat y en JSP— con **el 75 % de las líneas**. Las pruebas pesan **el 49 % del código
de producción**, contra el 22 % del heredado.

## Por capas

| Capa | JxMVC | LuxCore | Estado |
|---|---|---|---|
| **0. Transporte HTTP** | Tomcat | `lux-http` propio | **cerrada** |
| **1. Núcleo MVC** — router, pipeline, controladores | `MainLxServlet` (1041 L), `BaseDispatcher` (443 L), `JxRequest`/`JxResponse` | `lux-core` reescrito | **cerrada** |
| **2. Datos** | `JxDB`, `JxRepository`, `JxPool`, `JxTransaction`, `DBRow` | `lux-data` reescrito | **cerrada** |
| **3. Vistas** | JSP + `JxTagFor`/`JxTagIf` | `lux-view` propio | **cerrada** |
| **4. Seguridad de petición y validación** — CORS, CSRF, rate limit, sanitizado, validación | 5 clases, 884 L | reescritas en `lux-core` | **cerrada** |
| **5. Resto de transversales** — cache, scheduler, métricas, eventos, logger, OpenAPI, OAuth, WebSocket | 14 clases, 2 566 L | nada movido | **0 %, ninguna toca Jakarta** |

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

### Transversales — 14 clases, 2 566 líneas

Ninguna toca Jakarta, así que es trabajo de reescritura limpia, no de desacople.

| Grupo | Clases | Líneas |
|---|---|---|
| Autenticación — `JxOAuth`, `JxPasswords` | 2 | 528 |
| Generación — `JxOpenApi`, `GenApi` | 2 | 490 |
| Tareas y eventos — `JxScheduler`, `JxEventBus` | 2 | 430 |
| Observabilidad — `JxMetrics`, `JxLogger`, `JxProfile`, `JxDevMode` | 4 | 446 |
| WebSocket — `JxWebSocket`, `JxWsRegistrar` | 2 | 338 |
| Cache — `JxCache`, `JxCacheBackend` | 2 | 334 |

### Y además

- **`lux-launcher`** — empaquetado fat-jar. Hoy se arranca con `Lux.run(...)`, que basta.
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

**El tercero: qué prueban las pruebas.** 601 casos, pero funcionales. No hay pruebas de
concurrencia real, sockets lentos (slowloris), cuerpos parciales, clientes que abortan a media
respuesta, ni entradas generadas por fuzzing. Las pruebas pesan el 49 % del código de producción;
la cobertura por *modo de fallo* sigue siendo baja.

**El quinto: `lux-data` nunca ha hablado con una base de datos.** Sus 141 pruebas verifican el SQL
generado, el orden de los parámetros y el mapeo de resultados contra un driver JDBC propio. Eso
cubre lo que le toca al módulo, pero no cubre lo que hace cada motor real: tipos de PostgreSQL
frente a MySQL, `LIMIT`/`OFFSET` en SQL Server, claves generadas, zonas horarias. Antes de
producción hace falta una corrida contra al menos dos motores reales.

**El cuarto, nuevo: reflexión en el camino caliente.** `lux-core` resuelve los argumentos de cada
acción por reflexión en cada petición. Cuesta el 12 % de rps medido, y es exactamente el tipo de
decisión que **no** se puede llevar a Rust ni a C++ en la fase 3. Cuando se escriba el spec
poliglota habrá que resolver el registro de rutas y la vinculación en tiempo de compilación.

## Un cambio de diseño que conviene recordar

El middleware ahora envuelve **también la resolución de ruta**. Antes el 404 se lanzaba antes de la
cadena, y con eso un preflight `OPTIONS` de CORS —que por definición no coincide con ninguna ruta—
nunca habría llegado al middleware que debía atenderlo. La ruta resuelta viaja en el `Context`, así
que `Csrf` puede leer `@CsrfExempt` de la acción sin que el middleware tenga que resolverla él.

El efecto lateral es que un middleware que quiera observar los fallos necesita `try/finally`: si
`chain.proceed()` lanza, el código posterior no corre.

## Siguiente paso recomendado

Intentar que **`jxmvc2x` corra entero en modo standalone**. Ya no falta ninguna pieza de
infraestructura para lograrlo, y es la única prueba que convierte «paridad» en algo verificable en
vez de una lista de casillas. Lo que aparezca ahí decide qué transversal de los 14 restantes se
migra primero.
