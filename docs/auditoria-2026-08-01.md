# Auditoría: JxMVC 3.4.0 frente a LuxCore

Iniciada el 1 de agosto de 2026, al día tras cerrar los cuatro módulos y los transversales de
seguridad. **Puesta al día el 2 de agosto de 2026**, tras migrar observabilidad, autenticación y
tareas, añadir el adaptador de servlet y la aplicación de ejemplo, y poner el proyecto en
integración continua.

## Resumen en una línea

LuxCore sustituye a Tomcat, a JSP y al núcleo de JxMVC entero. El sitio de referencia corre sobre
LuxCore y el heredado ya no tiene nada que migrar: solo quedan sus 347 pruebas por portar.

## Dónde estamos

| | JxMVC 3.4.0 | LuxCore hoy |
|---|---|---|
| Clases de producción | 54 | 143 |
| Líneas de producción | 10 379 | 13 464 |
| Líneas de prueba | 2 248 | 7 440 |
| Pruebas | 347 | **1 227** |
| Dependencias en runtime | 0 (+ Jakarta *provided*) | **0 en el núcleo** |
| Referencias a `jakarta.*` | 14 clases | 4, todas en el adaptador de salida |
| Necesita contenedor | Tomcat 10.1+ | **no** |
| Arranque | 1164 ms | **106 ms** |
| Artefacto | 253 KB + Tomcat (~15 MB) | **297 KB** |

LuxCore hace hoy bastante más que JxMVC —incluye el servidor y el motor de vistas, que JxMVC
delegaba en Tomcat y en JSP— con **prácticamente las mismas líneas**. Las pruebas pesan **el 57 %
del código de producción**, contra el 22 % del heredado.

Las 4 clases con `jakarta.*` son `lux-adapter-servlet`, y están ahí a propósito: es la pieza que
deja desplegar la misma aplicación en Tomcat mientras las apps migran. El núcleo —`lux-http`,
`lux-core`, `lux-view`, `lux-data`— no las ve, y CI lo comprueba en cada push.

## Por capas

| Capa | JxMVC | LuxCore | Estado |
|---|---|---|---|
| **0. Transporte HTTP** | Tomcat | `lux-http` propio | **cerrada** |
| **1. Núcleo MVC** — router, pipeline, controladores | `MainLxServlet` (1041 L), `BaseDispatcher` (443 L), `JxRequest`/`JxResponse` | `lux-core` reescrito | **cerrada** |
| **2. Datos** | `JxDB`, `JxRepository`, `JxPool`, `JxTransaction`, `DBRow` | `lux-data` reescrito | **cerrada** |
| **3. Vistas** | JSP + `JxTagFor`/`JxTagIf` | `lux-view` propio | **cerrada** |
| **4. Seguridad de petición y validación** — CORS, CSRF, rate limit, sanitizado, validación | 5 clases, 884 L | reescritas en `lux-core` | **cerrada** |
| **5. Observabilidad, autenticación y tareas** — métricas, logger, scheduler, OAuth, contraseñas | 5 clases, 1 075 L | `Metrics`, `Log`, `AccessLog`, `Jobs`, `Cron`, `OAuth`, `Passwords` | **cerrada** |
| **6. Resto de transversales** — cache, eventos, OpenAPI, perfiles, WebSocket | 9 clases, 1 491 L | `Cache`, `Events`, `Profiles`, `OpenApi`, `WebSockets` | **cerrada** |
| **7. Salida** — seguir desplegando en Tomcat mientras se migra | — | `lux-adapter-servlet` | **cerrada** |

`lux-core` no es una transliteración de `MainLxServlet`: las 1041 líneas del pipeline de 15 etapas
se rehicieron como 11 clases con una responsabilidad cada una. El pipeline resultante es
autenticar → middleware → autorizar → vincular → invocar → renderizar, y cabe leerlo de corrido.

## En qué mejoramos (medido, no opinión)

- **Arranque: 1164 ms → 106 ms**, medidos en la misma corrida y en contenedores idénticos.
  11× más rápido con el framework entero levantado: la casi totalidad del arranque de JxMVC era
  Tomcat, y Tomcat ya no está.
- **Artefacto: 253 KB + ~15 MB de Tomcat → 297 KB en cuatro JAR.** Se despliega copiando archivos.
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
| HTTP/2 | baja | Tomcat lo daba; nadie lo está usando en las apps AUR |
| Rangos (`Range`) en estáticos | baja | vídeo y descargas reanudables |
| Recarga de certificado TLS | baja | renovar obliga a reiniciar el proceso |
| Sesiones distribuidas | baja | el almacén es por proceso; con varias instancias hace falta backend externo |

El log de acceso y el WebSocket ya no están en esta lista: `AccessLog` y `WebSockets` están
migrados y probados.

### Transversales — los 14, migrados

| Grupo | Heredado | LuxCore |
|---|---|---|
| Autenticación | `JxOAuth`, `JxPasswords` | `OAuth`, `Passwords` |
| Tareas | `JxScheduler` | `Jobs`, `Cron` |
| Observabilidad | `JxMetrics`, `JxLogger` | `Metrics`, `Log`, `AccessLog` |
| Generación | `JxOpenApi`, `GenApi` | `OpenApi` — desde el router, no escaneando el disco |
| Cache | `JxCache`, `JxCacheBackend` | `Cache` — sin registro global ni hilo de limpieza |
| WebSocket | `JxWebSocket`, `JxWsRegistrar` | `WebSockets`, `WebSocket` — RFC 6455 sobre lux-http |
| Eventos | `JxEventBus` | `Events` + `@Listens` |
| Perfiles | `JxProfile`, `JxDevMode` | `Profiles` |

### Y además

- **`lux-launcher`** — empaquetado fat-jar. Hoy se arranca con `Lux.run(...)`, que basta.
- ~~**`lux-adapter-servlet`**~~ — hecho, con 35 pruebas contra un doble de contenedor.
- ~~**El sitio de referencia sin Tomcat**~~ — hecho: `java/lux-web`, 65 pruebas de punta a punta.
- **Portar las 347 pruebas** del heredado. Es lo único que queda de la fase 2.

## Riesgos

**El grande sigue siendo la madurez.** El parser HTTP de LuxCore tiene tres días y 162 pruebas. El
de Tomcat tiene 25 años, miles de pruebas y un historial de CVEs ya corregidos que nosotros vamos a
tener que descubrir por nuestra cuenta. Los 51 ms de arranque son reales; la robustez todavía no
está demostrada. Que la auditoría encontrara una inyección CRLF a las pocas horas de escribir el
módulo es la prueba de que este riesgo no es teórico. Mitigación seria: fuzzing del parser y un
banco de conformidad HTTP/1.1 antes de poner esto delante de tráfico real.

**El segundo: el rps de 94 610 no valía · cerrado.** La corrida del harness con los seis
contendientes lo desmiente y lo sustituye. LuxCore lidera los tres endpoints —26 425, 25 431 y
25 931— y arranca en 106 ms contra los 451 del siguiente. En `/db`, que es el que mide el
framework haciendo trabajo de aplicación, saca un 38 % a JxMVC.

El RSS, que a mediodía era el punto flojo con 298 MB, resultó ser un fallo y no un peso: el
vigilante programaba una tarea por petición que al cancelarse no salía de la cola. Corregido,
son **136,4 MB — el más bajo de los seis**. Sigue por encima de la meta absoluta de 120 MB.

**El tercero: qué prueban las pruebas · rebajado.** Ya hay concurrencia (1000 peticiones
simultáneas), sockets lentos, cuerpos que mienten, clientes que abortan a media respuesta y
fuzzing dirigido del parser — todo en `HostileTests`. Lo que falta es el eje del tiempo: nada
corre más de unos segundos, así que una fuga de memoria o de descriptores no tendría cómo
aparecer. Y «fuzzing dirigido» no es un banco de conformidad HTTP/1.1 reconocido.

**El quinto: `lux-data` nunca ha hablado con una base de datos · cerrado.** Los 47 casos de
`MotorTests` corren contra H2, PostgreSQL 16 y MySQL 8 reales, y en CI la corrida **falla** si
algún motor no estaba accesible, para que no se pueda mentir por omisión. Encontró un fallo real:
`Row.as()` ignoraba `@Column`. Siguen sin probarse SQL Server y Oracle, y el comportamiento ante
caída y reconexión del motor.

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

## Lo que se hizo entre el 1 y el 2 de agosto

- **Observabilidad**: `Metrics` (por ruta, con percentiles), `Log` y `AccessLog`.
- **Autenticación**: `OAuth` (OIDC con PKCE obligatorio, firma RS256 verificada contra el JWKS del
  proveedor, `alg:none` rechazado) y `Passwords` (PBKDF2-HMAC-SHA256, 210 000 iteraciones).
- **Tareas**: `Jobs` y `Cron`; y un cliente HTTP propio, `Http`.
- **Cabeceras de seguridad** (`SecurityHeaders`) y la batería de clientes hostiles.
- **`lux-adapter-servlet`**: la puerta de salida hacia Tomcat.
- **`java/ejemplo`**: la primera aplicación completa encima del framework, que destapó cuatro
  huecos que ninguna lista de casillas habría visto.
- **`lux-data` contra motores reales**: H2, PostgreSQL 16 y MySQL 8.
- **Integración continua**: la suite corre en Linux sobre JDK 21 y 25 en cada push, con las bases
  de datos levantadas, comprobando además que el núcleo no arrastra dependencias y que el sitio no
  se ha desincronizado de sus fuentes.

## Errores encontrados en esta puesta al día

Dos, y los dos por ejecutar cosas que hasta ahora solo se habían leído:

1. **La aplicación de ejemplo servía sin cabeceras de seguridad.** `SecurityHeaders` estaba escrito
   y probado, pero nadie lo había enchufado en la app que el proyecto enseña como referencia.
   Corregido, con 7 pruebas nuevas que comprueban también que las cabeceras viajan en una respuesta
   *rechazada*, no solo en la feliz.
2. **LuxCore no podía entrar en su propio banco de pruebas.** `benchmarks/docker/apps/luxcore`
   fijaba `lux-core:0.1.0`; al subir el proyecto a 0.2.0 la imagen dejó de construir, en silencio,
   y por eso LuxCore nunca apareció en `RESULTS-docker.md`. Además, la tabla se agregaba filtrando
   por `localhost:8080` fijo mientras la corrida usaba otro puerto, así que aunque el CSV tuviera
   datos la tabla salía vacía. Las dos cosas están corregidas, y la versión ahora se lee de
   `java/pom.xml` para que no vuelva a pasar al subir de versión.

## Siguiente paso recomendado

**Portar las 347 pruebas del núcleo heredado** al runner propio. Es lo único que queda de la
fase 2: los transversales están migrados y el sitio de referencia ya corre standalone como
`lux-web`. Después, la fase 3 — el contrato neutral y las implementaciones en Rust y C++.
