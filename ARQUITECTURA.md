# Arquitectura de LuxCore

Este documento describe hacia dónde va el código. El estado actual del repositorio es todavía el
árbol heredado de JxMVC 3.4.0 (ver [ORIGEN.md](ORIGEN.md)); nada de lo que sigue está implementado.

## El hallazgo que define el plan

Antes de diseñar nada se midió el acoplamiento real del núcleo heredado al contenedor de servlets.
El resultado es mejor de lo que sugiere el README de JxMVC:

- De **54 clases** en `legacy/jxmvc-core/src/main/java/jxmvc/core/`, **40 no importan nada de `jakarta.*`**.
  `JxDB`, `JxJson`, `JxRepository`, `JxValidation`, `JxOAuth`, `JxCache`, `JxScheduler`, `JxPool`,
  `JxMetrics`, `JxOpenApi`, `JxServiceRegistry`, `JxTransaction`… todo eso es Java puro.
- Las **14 clases restantes** tocan Jakarta con 1–4 referencias cada una, salvo dos:
  `MainLxServlet` (1046 líneas, 6 refs) y `JxWsRegistrar` (8 refs).
- La superficie real son **cinco tipos**: `HttpServletRequest`, `HttpServletResponse`,
  `HttpSession`, `Cookie` y `Part`. Más JSP (`TagSupport`, en `JxTagFor`/`JxTagIf`) y
  `ServerContainer` de WebSocket.

Es decir: el framework nunca estuvo realmente casado con Tomcat. Está casado con cinco interfaces.
Y `JxRequest`/`JxResponse` ya son exactamente la costura por donde separarlas — envuelven el
request y el response crudos y exponen a los controladores la API propia del framework.

Cambiar lo que envuelven es un trabajo acotado a 14 archivos, no una reescritura de 55.

## Estructura destino

Todo en minúscula, incluidos los módulos y los directorios que genere el framework para una
aplicación nueva.

```text
spec/                     Contrato del kernel, versionado, neutral respecto al lenguaje
java/
  lux-http/               Servidor HTTP/1.1 propio (hilos virtuales). Cero deps.
  lux-core/               Router, pipeline, DI, configuración, resultados. Cero deps.
  lux-view/               Motor de plantillas propio (sustituye a JSP)
  lux-data/               JxDB, JxRepository, JxPool, JxTransaction — se mudan casi tal cual
  lux-adapter-servlet/    Compatibilidad Jakarta/Tomcat para las apps AUR ya desplegadas
  lux-launcher/           Fat-jar: java -jar app.jar
legacy/
  jxmvc-core/             Núcleo heredado — origen de la migración
  jxmvc2x/                Web de referencia en JSP — banco de pruebas de lux-view
rust/                     Segunda implementación
cpp/                      Tercera implementación
benchmarks/               Harness comparativo (heredado, + LuxCore)
```

## Estilo del código

- **Sin comentarios.** Ni javadoc decorativo, ni cabeceras de autoría, ni bloques que repiten lo
  que dice el código. Si un fragmento necesita explicación, el problema es el fragmento.
- **Nombres completos.** `readChunkSize`, no `rcs`. `maxKeepAliveRequests`, no `mkar`.
- **Métodos cortos.** Uno hace una cosa. `MainLxServlet` con 1046 líneas es el contraejemplo que
  motiva esta regla.
- **Todo en minúscula** en rutas, módulos y directorios generados.
- Identificadores en inglés, mensajes de error y documentación en español.

## Fase 1 — El núcleo que se levanta solo

### 1.1 `lux-http` — el servidor · **completo**

HTTP/1.1 con un hilo virtual por conexión (Java 21+). 31 clases, cero dependencias, 144 pruebas.

Cubre: parseo de línea de petición y cabeceras, keep-alive con reutilización de conexión,
`Content-Length` y `Transfer-Encoding: chunked` en ambos sentidos, `Expect: 100-continue`,
decodificación porcentual de path y query, HEAD, redirecciones, respuestas en streaming, **TLS**,
**cookies**, **sesiones**, **multipart**, **gzip** y **archivos estáticos**.

Los límites son explícitos y están en `ServerOptions`: tamaño de línea de petición, tamaño y
número de cabeceras, tamaño de cuerpo, conexiones concurrentes, timeout de inactividad, timeout de
handler, peticiones por conexión y margen de apagado. Rechaza cabeceras plegadas,
`Content-Length` duplicado, la combinación `Content-Length` + `Transfer-Encoding`, `Host` ausente
o duplicado, y caracteres de control en las cabeceras de respuesta.

Tres decisiones que conviene recordar:

- **El watchdog es un solo hilo compartido**, no un `Future` por petición. Ninguno de los dos puede
  matar un handler colgado —Java no permite matar hilos—, pero el watchdog cierra el socket y libera
  la conexión sin cobrar nada en el camino rápido.
- **Las sesiones se barren por muestreo**, cada 256 creaciones, en vez de con un hilo de limpieza.
  Un hilo más para algo que puede ir a coste amortizado no se justifica.
- **El techo de conexiones cierra en el accept**, sin leer la petición ni responder 503. Responder
  exigiría leerla, que es justo el trabajo que el techo existe para no hacer.

**Cambio respecto al plan: hilos virtuales sobre IO bloqueante, no selectores NIO.** El plan decía
`java.nio`. Con hilos virtuales, el IO bloqueante ya no bloquea un hilo del sistema operativo:
un `ServerSocket` con un hilo virtual por conexión rinde igual que un selector y se lee de corrido.
El selector NIO era la respuesta correcta antes de Java 21; hoy sería complejidad sin beneficio, y
contradice el principio de que el núcleo se pueda leer entero.

**Sobre `com.sun.net.httpserver`:** el JDK ya trae un servidor HTTP y es tentador por gratis. No
sirve aquí — es un servidor de juguete, sin keep-alive decente ni control de backpressure.
Escribir el nuestro es el punto entero del proyecto.

Pendiente en este módulo, ya sin bloquear a nadie: WebSocket, HTTP/2, log de acceso, cabecera
`Range` en estáticos y sesiones distribuidas.

### 1.2 La costura

Definir `lux.http.Request` y `lux.http.Response` como interfaces puras del JDK. `JxRequest` y
`JxResponse` pasan a envolver esas interfaces en lugar de las de Jakarta. Este es el cambio que
desbloquea todo lo demás.

### 1.3 `MainLxServlet` → `lux-core` · **hecho**

Las 1041 líneas del pipeline de 15 etapas no se transliteraron: se rehicieron como 11 clases con
una responsabilidad cada una. El pipeline quedó en seis pasos que se leen de corrido:

```text
autenticar → middleware → autorizar → vincular → invocar → renderizar
```

- `Router` / `RoutePattern` / `RouteEntry` — rutas por anotación, plantillas `{var}`, comodín
  final, y convención por nombre de clase. La ruta literal siempre gana a la variable.
- `Dispatcher` — el pipeline. 404 y 405 (con cabecera `Allow`) se resuelven antes de construir
  nada.
- `Binder` — `@Path`, `@Query`, `@Body`, `@Header`, `@CookieValue`, más `Context`, `Request`,
  `Response`, `Session` y `Principal` por tipo.
- `Registry` — inyección por campo y por constructor, singletons, `@Service` bajo demanda,
  detección de ciclos con la cadena completa en el mensaje.
- `Json` — escritura, lectura y vinculación a records, beans, enums, `Optional` y tipos de
  `java.time`. Detecta ciclos al serializar.
- `Result` — `text`, `html`, `json`, `view`, `redirect`, `noContent`, con estado y cabeceras.
- `Config` — properties del classpath y del disco, variables `LUX_*` y propiedades `lux.*`.

**Autenticar va antes del middleware, autorizar después.** Así el middleware ve el `Principal`
—que es lo que casi siempre necesita— y a la vez puede envolver los 401 y 403 para registrarlos.

**Lo que devuelve una acción decide el formato:** `Result` se respeta tal cual, `String` sale como
texto plano, `null` es un 204 y cualquier otra cosa se serializa a JSON. Sin anotación de por
medio.

### 1.4 Adiós JSP

`JxTagFor` y `JxTagIf` dependen de `jakarta.servlet.jsp` y se eliminan. Los sustituye `lux-view`:
plantillas compiladas a Java en el arranque, cero dependencias, sin motor de JSP detrás.

### 1.5 El lanzador · **hecho**

```java
Lux.run(8080, ApiController.class);
```

o con todo declarado:

```java
Lux.app()
   .loadConfig()
   .controllers(ApiController.class, AdminController.class)
   .routes(r -> r.get("/salud", ctx -> "ok"))
   .service(new Catalogo())
   .authenticator(ctx -> tokens.verify(ctx.header("Authorization")))
   .use((ctx, chain) -> { log(ctx); return chain.proceed(ctx); })
   .start();
```

Imprime host, puerto, número de rutas y tiempo de arranque. `lux-launcher` como módulo aparte
—empaquetado fat-jar— queda para la fase 2; para arrancar ya no hace falta.

### 1.6 No romper lo que ya está en producción

`lux-adapter-servlet` implementa `lux.http.Request`/`Response` sobre `HttpServletRequest`/`Response`.
Las apps AUR que hoy dependen de JxMVC (Academia, Intranet, NFC Intranet) siguen desplegando en
Tomcat sin cambiar una línea, mientras el modo standalone es el camino nuevo.

Esto es lo que convierte la refundación en una migración y no en una ruptura.

### Metas de la fase

Mismo harness, mismas condiciones que la línea base:

| Métrica | JxMVC hoy | Meta LuxCore | Medido en local | Referencia a batir |
|---|---|---|---|---|
| Arranque | 1392 ms | < 150 ms | **28 ms** | javalin 466 |
| RSS | 471.8 MB | < 120 MB | sin medir en serio | micronaut 331.7 |
| rps `/json` | 43 315 | ≥ 48 000 | 100 554 ⚠ no comparable | javalin 47 667 |
| JAR runtime | 253 KB | ≤ 400 KB | **64 KB** | — |
| Dependencias | 0 | 0 | **0** | spring: decenas |

RSS y rps solo cuentan medidos en el harness de `benchmarks/docker`, en el mismo Arch bare-metal
que usó el paper. Ver [docs/mediciones-locales.md](docs/mediciones-locales.md).

## Fase 2 — Paridad

Migrar las 41 clases puras a los módulos nuevos. Portar las 347 pruebas al runner propio
(`JxTestSuite`, que ya existe y no usa JUnit). WebSockets propios sobre `lux-http`, sustituyendo
`jakarta.websocket`. `lux-view` cubriendo todo lo que hoy hace JSP en `JxMVC2x`.

**Criterio de cierre:** `JxMVC2x` corre entero en modo standalone. Esa es la prueba de que la
paridad es real y no una lista de casillas marcadas.

## Fase 3 — El framework de frameworks

**Java se termina primero.** Ni el spec ni Rust ni C++ se empiezan hasta que la fase 2 esté
cerrada. Un spec escrito antes de tener una implementación completa describe lo que uno imagina,
no lo que el framework necesita.

Aquí LuxCore deja de ser «JxMVC v4».

`SPEC/lux-kernel.md` define el contrato en lenguaje neutro: modelo de rutas, forma del
request/response, ciclo del pipeline, contrato de middleware, inyección de dependencias,
configuración, formato de errores. Acompañado de un banco de pruebas de conformidad —peticiones
HTTP y respuestas esperadas— que **cualquier** implementación debe pasar.

Después, `rust/` y luego `cpp/`.

**Rust va primero, y no es arbitrario.** Java resuelve el registro de rutas con reflexión y
anotaciones en tiempo de ejecución. Ni Rust ni C++ tienen eso. Si el spec se escribe mirando solo
a Java, saldrá contaminado de supuestos —GC, reflexión, jerarquías de clases— que no se ven hasta
que alguien intenta implementarlo sin ellos. Rust es el que más presión pone sobre el diseño:
sin GC, sin reflexión, con ownership. La restricción de que el registro de rutas se resuelva en
tiempo de compilación **se descubre escribiendo la segunda implementación**, no antes.

El mismo harness corre las tres. Ese es el resultado publicable, y es bastante más grande que un
framework de Java.

## Fuera de alcance

`19.Soft_JXMVC` y `AUP_Papers/13.-JxMVC_SPE/` no se tocan. Los pendientes del artículo (benchmark
`/db` en Arch, DOI de Zenodo, autoría y ORCID) siguen su curso aparte; LuxCore no los bloquea ni
depende de ellos.
