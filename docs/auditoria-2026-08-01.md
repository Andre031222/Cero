# Auditoría interna del núcleo · 1 de agosto de 2026

Hecha al día siguiente de cerrar los cuatro módulos y los transversales de seguridad. **Puesta al
día el 2 de agosto de 2026**, tras añadir observabilidad, autenticación y tareas, el adaptador de
servlet y la aplicación de ejemplo, y poner el proyecto en integración continua.

## Resumen en una línea

El framework sirve HTTP, renderiza vistas y habla con la base de datos sin contenedor de servlets
detrás, y el sitio de referencia corre encima.

## Dónde estamos

| | |
|---|---|
| Clases de producción | 143 |
| Líneas de producción | 13 464 |
| Líneas de prueba | 7 440 |
| Pruebas | **1 227** |
| Dependencias en ejecución | **0 en el núcleo** |
| Referencias a `jakarta.*` | 4, todas en el adaptador de salida |
| Necesita contenedor | **no** |
| Arranque | **106 ms** |
| Artefacto | **297 KB** |

Las pruebas pesan **el 57 % del código de producción**.

Las 4 clases con `jakarta.*` son `cero-adapter-servlet`, y están ahí a propósito: es la pieza que
deja desplegar la misma aplicación en Tomcat. El núcleo —`cero-http`, `cero-core`, `cero-view`,
`cero-data`— no las ve, y CI lo comprueba en cada push.

## Por capas

| Capa | Dónde está | Estado |
|---|---|---|
| **0. Transporte HTTP** | `cero-http` | **cerrada** |
| **1. Núcleo MVC** — router, pipeline, controladores | `cero-core` | **cerrada** |
| **2. Datos** | `cero-data` | **cerrada** |
| **3. Vistas** | `cero-view` | **cerrada** |
| **4. Seguridad de petición y validación** — CORS, CSRF, rate limit, sanitizado, validación | `cero-core` | **cerrada** |
| **5. Observabilidad, autenticación y tareas** | `Metrics`, `Log`, `AccessLog`, `Jobs`, `Cron`, `OAuth`, `Passwords` | **cerrada** |
| **6. Resto de transversales** | `Cache`, `Events`, `Profiles`, `OpenApi`, `WebSockets` | **cerrada** |
| **7. Salida a un contenedor** | `cero-adapter-servlet` | **cerrada** |

El pipeline son 11 clases con una responsabilidad cada una: autenticar → middleware → autorizar →
vincular → invocar → renderizar. Cabe leerlo de corrido.

## Lo medido, no opinado

- **Arranque: 106 ms**, en contenedores idénticos y en la misma corrida que sus rivales.
- **Artefacto: 297 KB en cuatro JAR.** Se despliega copiando archivos.
- **Dependencias: 0 reales.** Nada fuera del JDK, ni en compilación ni en ejecución. El driver
  JDBC lo pone la aplicación.
- **Endurecimiento explícito.** Los límites están en `ServerOptions`, versionados, y las pruebas
  los ejercitan: rechaza cabeceras plegadas, `Content-Length` duplicado y `Content-Length` junto a
  `Transfer-Encoding` — los tres vectores clásicos de *request smuggling*.

## Capa 0 — lo que se cerró

Todo lo que antes ponía el contenedor, más los cuatro huecos que bloqueaban producción:

| Pieza | Cómo quedó |
|---|---|
| **TLS / HTTPS** | `SSLContext` del JDK, helper `Tls.fromKeystore` |
| **Techo de conexiones** | contador atómico; por encima del techo el socket se cierra en el accept |
| **Timeout de handler** | watchdog de un solo hilo compartido; sin coste en el camino rápido cuando está desactivado |
| **Apagado ordenado** | `stop()` deja de aceptar, corta el keep-alive en curso y espera `shutdownGraceMillis` a las peticiones en vuelo |
| Cookies | `Cookie` con validación de nombre y valor; parseo de cabeceras `Cookie` repetidas |
| Sesiones | almacén en memoria, id de 256 bits de `SecureRandom`, caducidad por inactividad, barrido periódico; cookie `HttpOnly`, `SameSite=Lax`, `Secure` automático bajo TLS |
| Multipart | `Multipart`/`Part`, con techo de partes |
| gzip | por negociación de contenido, solo tipos comprimibles y por encima de `gzipMinBytes` |
| Archivos estáticos | `StaticFiles` con ETag, `If-None-Match`, `Last-Modified` y bloqueo de path traversal |
| Validación de `Host` | 400 si falta en HTTP/1.1 o si viene duplicado |

**Corregido durante la auditoría: inyección CRLF en cabeceras de respuesta.**
`res.header(nombre, valor)` escribía el valor tal cual: un valor con `\r\n` procedente de entrada
de usuario permitía inyectar cabeceras o partir la respuesta. Se rechazan caracteres de control en
nombre y valor, con verificación también al escribir para que no se pueda esquivar por `headers()`.

## Lo que sigue faltando

| Hueco | Gravedad | Nota |
|---|---|---|
| HTTP/2 | baja | ninguna aplicación lo necesita todavía |
| Rangos (`Range`) en estáticos | baja | vídeo y descargas reanudables |
| Recarga de certificado TLS | baja | renovar obliga a reiniciar el proceso |
| Sesiones distribuidas | baja | el almacén es por proceso; con varias instancias hace falta backend externo |

`cero-launcher` —el empaquetado fat-jar— sigue pendiente; para arrancar basta con `Cero.run(...)`.

## Riesgos

**El grande es la madurez.** El parser HTTP tiene tres días y 162 pruebas. El de Tomcat tiene 25
años, miles de pruebas y un historial de CVEs ya corregidos que aquí habrá que descubrir por
cuenta propia. Los 106 ms de arranque son reales; la robustez todavía no está demostrada. Que la
auditoría encontrara una inyección CRLF a las pocas horas de escribir el módulo es la prueba de
que este riesgo no es teórico. Mitigación seria: fuzzing del parser y un banco de conformidad
HTTP/1.1 antes de poner esto delante de tráfico real.

**El segundo: el rps de 94 610 no valía · cerrado.** La corrida del harness lo desmiente y lo
sustituye. Cero lidera los tres endpoints —26 425, 25 431 y 25 931— y arranca en 106 ms contra los
451 del siguiente.

El RSS, que a mediodía era el punto flojo con 298 MB, resultó ser un fallo y no un peso: el
vigilante programaba una tarea por petición que al cancelarse no salía de la cola. Corregido,
son **136,4 MB — el más bajo de la tabla**. Sigue por encima de la meta absoluta de 120 MB.

**El tercero: qué prueban las pruebas · rebajado.** Ya hay concurrencia (1000 peticiones
simultáneas), sockets lentos, cuerpos que mienten, clientes que abortan a media respuesta y
fuzzing dirigido del parser — todo en `HostileTests`. Lo que falta es el eje del tiempo: nada
corre más de unos segundos, así que una fuga de memoria o de descriptores no tendría cómo
aparecer. Y «fuzzing dirigido» no es un banco de conformidad HTTP/1.1 reconocido.

**El cuarto: `cero-data` nunca había hablado con una base de datos · cerrado.** Los 47 casos de
`MotorTests` corren contra H2, PostgreSQL 16 y MySQL 8 reales, y en CI la corrida **falla** si
algún motor no estaba accesible, para que no se pueda mentir por omisión. Encontró un fallo real:
`Row.as()` ignoraba `@Column`. Siguen sin probarse SQL Server y Oracle, y el comportamiento ante
caída y reconexión del motor.

**El quinto: reflexión en el camino caliente.** `cero-core` resuelve los argumentos de cada acción
por reflexión en cada petición. Cuesta el 12 % de rps medido, y es exactamente el tipo de decisión
que **no** se puede llevar a Rust ni a C++ en la fase 3. Cuando se escriba el spec poliglota habrá
que resolver el registro de rutas y la vinculación en tiempo de compilación.

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
- **`cero-adapter-servlet`**: la puerta de salida hacia Tomcat.
- **`java/ejemplo`**: la primera aplicación completa encima del framework, que destapó cuatro
  huecos que ninguna lista de casillas habría visto.
- **`cero-data` contra motores reales**: H2, PostgreSQL 16 y MySQL 8.
- **Integración continua**: la suite corre en Linux sobre JDK 21 y 25 en cada push, con las bases
  de datos levantadas, comprobando además que el núcleo no arrastra dependencias y que el sitio no
  se ha desincronizado de sus fuentes.

## Errores encontrados en esta puesta al día

Dos, y los dos por ejecutar cosas que hasta ahora solo se habían leído:

1. **La aplicación de ejemplo servía sin cabeceras de seguridad.** `SecurityHeaders` estaba escrito
   y probado, pero nadie lo había enchufado en la app que el proyecto enseña como referencia.
   Corregido, con 7 pruebas nuevas que comprueban también que las cabeceras viajan en una respuesta
   *rechazada*, no solo en la feliz.
2. **El framework no podía entrar en su propio banco de pruebas.** La imagen del harness fijaba
   `cero-core:0.1.0`; al subir el proyecto a 0.2.0 dejó de construir, en silencio, y por eso Cero
   nunca apareció en `RESULTS-docker.md`. Además, la tabla se agregaba filtrando por
   `localhost:8080` fijo mientras la corrida usaba otro puerto, así que aunque el CSV tuviera datos
   la tabla salía vacía. Las dos cosas están corregidas, y la versión ahora se lee de
   `java/pom.xml` para que no vuelva a pasar al subir de versión.

## Siguiente paso recomendado

Cerrar el eje del tiempo: carga sostenida con vigilancia de RSS y de descriptores, que es el único
riesgo de la lista que no se cierra escribiendo más pruebas cortas. Después, la fase 3 — el
contrato neutral y las implementaciones en Rust y C++.
