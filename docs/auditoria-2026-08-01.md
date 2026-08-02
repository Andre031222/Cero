# Auditoría: JxMVC 3.4.0 frente a LuxCore

1 de agosto de 2026. Estado tras escribir `lux-http`.

## Resumen en una línea

LuxCore reemplazó **Tomcat**, no JxMVC. La capa de transporte está hecha y es medible mejor;
las otras tres capas del framework siguen enteras en `legacy/`.

## Dónde estamos

| | JxMVC 3.4.0 | LuxCore hoy |
|---|---|---|
| Clases de producción | 54 | 20 |
| Líneas de producción | 10 379 | 1 585 |
| Líneas de prueba | 2 248 | 269 |
| Pruebas | 347 | 30 |
| Dependencias en runtime | 0 (+ Jakarta *provided*) | **0, ninguna** |
| Necesita contenedor | Tomcat 10.1+ | **no** |
| Arranque | 1392 ms | **17 ms** |
| Artefacto | 253 KB + Tomcat (~15 MB) | **36 KB** |

LuxCore es hoy el **15 %** de JxMVC en líneas. Ese 15 % es exactamente la capa que JxMVC nunca
tuvo que escribir porque se la daba Tomcat.

## Por capas

| Capa | JxMVC | LuxCore | Estado |
|---|---|---|---|
| **0. Transporte HTTP** | Tomcat | `lux-http` propio | **hecho, con huecos** |
| **1. Núcleo MVC** — router, pipeline de 15 etapas, controladores | `MainLxServlet` (1041 L), `BaseDispatcher` (443 L), `JxRequest`/`JxResponse` | nada | **0 %** |
| **2. Transversales** — DI, datos, cache, seguridad, validación, scheduler, métricas | 40 clases puras, ~7 500 L | nada movido | **0 % movido, 100 % portable** |
| **3. Vistas** | JSP + `JxTagFor`/`JxTagIf` | nada | **0 %, hay que reescribir** |

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

## Lo que falta

### Capa 0 — huecos de `lux-http`

Ordenados por lo que bloquea, no por tamaño.

| Hueco | Hoy en JxMVC | Gravedad |
|---|---|---|
| **TLS / HTTPS** | Tomcat | **bloquea producción** |
| **Límite de conexiones concurrentes** | `maxConnections` de Tomcat | **bloquea producción** — hoy son hilos virtuales sin techo: una avalancha de conexiones lentas agota la memoria |
| **Timeout de handler** | Tomcat | alta — hoy solo hay timeout de inactividad de socket; un handler colgado retiene la conexión indefinidamente |
| **Apagado ordenado** | Tomcat drena | alta — `stop()` cierra el socket y no espera a las peticiones en vuelo |
| Cookies y sesiones | `HttpSession`, `Cookie` | alta — las usan `JxCsrf`, `JxController`, `JxAuthProvider` |
| Multipart / subida de archivos | `jakarta.servlet.http.Part` | alta — la usa `JxRequest` |
| gzip | `JxGzip` (acoplado a servlet) | media |
| Archivos estáticos | `DefaultServlet` de Tomcat | media |
| WebSocket | `jakarta.websocket` | media — `JxWebSocket`, `JxWsRegistrar` |
| Validación de `Host` | Tomcat | baja |
| HTTP/2 | Tomcat | baja |
| Log de acceso | Tomcat | baja |

### Corregido durante esta auditoría

**Inyección CRLF en cabeceras de respuesta.** `res.header(nombre, valor)` escribía el valor tal
cual: un valor con `\r\n` procedente de entrada de usuario permitía inyectar cabeceras o partir
la respuesta. Tomcat valida esto desde hace años; nosotros no. Corregido — se rechazan caracteres
de control en nombre y valor, con verificación al escribir para que no se pueda esquivar por
`headers()`. Dos pruebas nuevas.

### Capas 1–3 — el trabajo grande

- `MainLxServlet` (1041 L) → `LuxDispatcher`, partido en piezas legibles.
- `JxRequest`/`JxResponse` (367 L) → envolver `lux.http.Request`/`Response` en vez de Jakarta.
- 12 clases más con Jakarta: `BaseDispatcher`, `BaseCorsResolver`, `JxController`, `JxCsrf`,
  `JxFilterContext`, `JxGzip`, `JxAuthProvider`, `JxWebSocket`, `JxWsRegistrar`.
- `JxTagFor`, `JxTagIf` → se eliminan, los sustituye `lux-view`.
- 40 clases puras → mudanza mecánica a `lux-core` y `lux-data`.
- `lux-adapter-servlet` → para que Academia, Intranet y NFC Intranet no se rompan.
- Portar las 347 pruebas.

## Riesgos

**El grande: madurez.** El parser HTTP de LuxCore tiene un día y 30 pruebas. El de Tomcat tiene
25 años, miles de pruebas y un historial de CVEs ya corregidos que nosotros vamos a tener que
descubrir por nuestra cuenta. Los 17 ms de arranque son reales; la robustez todavía no está
demostrada. Mitigación seria: fuzzing del parser, y un banco de conformidad HTTP/1.1 antes de
poner esto delante de tráfico real.

**El segundo: el rps de 107 779 no vale.** Se midió con cliente y servidor en la misma máquina,
con `ab`, sin aislamiento. No es comparable con la tabla del paper y no debe citarse. El número
honesto sale del harness de `benchmarks/docker` con LuxCore como sexto contendiente, en el mismo
Arch bare-metal.

**El tercero: cobertura.** 17 % de líneas de prueba frente al 22 % del heredado, y las de LuxCore
son funcionales, no adversariales. Falta probar concurrencia, sockets lentos, cuerpos parciales y
clientes que abortan a media respuesta.

## Siguiente paso recomendado

Cerrar los cuatro huecos que bloquean producción en `lux-http` —TLS, techo de conexiones, timeout
de handler, apagado ordenado— antes de empezar `lux-core`. Son pequeños y contenidos ahora; con
el router y el pipeline encima, tocar el transporte cuesta el triple.
