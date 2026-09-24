# Arquitectura de Cero

Este documento describe hacia dónde va el código. **Las fases 1 y 2 están cerradas**: siete
módulos, el sitio de referencia sobre el propio framework y 1 227 pruebas. Lo que sigue es la
fase 3.

## El hallazgo que define el plan

Una aplicación web en Java parece atada al contenedor de servlets, y al medirlo no lo está: la
superficie que de verdad se usa son **cinco tipos** —`HttpServletRequest`, `HttpServletResponse`,
`HttpSession`, `Cookie` y `Part`—, más JSP para las vistas y `ServerContainer` para WebSocket.

Todo lo demás —acceso a datos, JSON, validación, OAuth, caché, tareas, pool, métricas— es Java
puro y no sabe que hay un contenedor detrás. El plan sale de ahí: escribir esos cinco tipos en
casa, sobre un servidor propio, y dejar intacto el resto.

## Estructura destino

Todo en minúscula, incluidos los módulos y los directorios que genere el framework para una
aplicación nueva.

```text
spec/                     Contrato del kernel, versionado, neutral respecto al lenguaje
java/
  cero-http/               Servidor HTTP/1.1 propio (hilos virtuales). Cero deps.
  cero-core/               Router, pipeline, DI, configuración, resultados. Cero deps.
  cero-view/               Motor de plantillas propio (sustituye a JSP)
  cero-data/               Db, Repository, Pool, Tx — JDBC directo, sin ORM
  cero-adapter-servlet/    Compatibilidad Jakarta/Tomcat para desplegar en un contenedor
  ejemplo/                Aplicación pequeña de punta a punta
  cero-launcher/           Fat-jar: java -jar app.jar   (pendiente)
rust/                     Segunda implementación
cpp/                      Tercera implementación
benchmarks/               Harness comparativo
```

## Estilo del código

- **Sin comentarios.** Ni javadoc decorativo, ni cabeceras de autoría, ni bloques que repiten lo
  que dice el código. Si un fragmento necesita explicación, el problema es el fragmento.
- **Nombres completos.** `readChunkSize`, no `rcs`. `maxKeepAliveRequests`, no `mkar`.
- **Métodos cortos.** Uno hace una cosa. Un servlet de despacho de mil líneas es el contraejemplo
  que motiva esta regla: se lee entero o no se entiende ninguna de sus partes.
- **Todo en minúscula** en rutas, módulos y directorios generados.
- Identificadores en inglés, mensajes de error y documentación en español.

## Fase 1 — El núcleo que se levanta solo

### 1.1 `cero-http` — el servidor · **completo**

HTTP/1.1 con un hilo virtual por conexión (Java 25+). 31 clases, cero dependencias, 144 pruebas.

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

Después se le añadieron WebSocket (RFC 6455), rangos en estáticos, recarga de certificado TLS
sin reiniciar y un almacén de sesiones enchufable para varias instancias. Queda **HTTP/2**, que
es un proyecto aparte y que un proxy inverso resuelve mientras tanto.

### 1.2 La costura

Definir `cero.http.Request` y `cero.http.Response` como interfaces puras del JDK. `JxRequest` y
`JxResponse` pasan a envolver esas interfaces en lugar de las de Jakarta. Este es el cambio que
desbloquea todo lo demás.

### 1.3 `MainLxServlet` → `cero-core` · **hecho**

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
- `Config` — properties del classpath y del disco, variables `CERO_*` y propiedades `cero.*`.

**Autenticar va antes del middleware, autorizar después.** Así el middleware ve el `Principal`
—que es lo que casi siempre necesita— y a la vez puede envolver los 401 y 403 para registrarlos.

**Lo que devuelve una acción decide el formato:** `Result` se respeta tal cual, `String` sale como
texto plano, `null` es un 204 y cualquier otra cosa se serializa a JSON. Sin anotación de por
medio.

### 1.4 Adiós JSP

Las vistas no dependen de `jakarta.servlet.jsp`. Las resuelve `cero-view`: plantillas compiladas a
Java en el arranque, cero dependencias, sin motor de JSP detrás.

### 1.5 El lanzador · **hecho**

```java
Cero.run(8080, ApiController.class);
```

o con todo declarado:

```java
Cero.app()
   .loadConfig()
   .controllers(ApiController.class, AdminController.class)
   .routes(r -> r.get("/salud", ctx -> "ok"))
   .service(new Catalogo())
   .authenticator(ctx -> tokens.verify(ctx.header("Authorization")))
   .use((ctx, chain) -> { log(ctx); return chain.proceed(ctx); })
   .start();
```

Imprime host, puerto, número de rutas y tiempo de arranque. `cero-launcher` como módulo aparte
—empaquetado fat-jar— queda para la fase 2; para arrancar ya no hace falta.

### 1.6 No romper lo que ya está en producción

`cero-adapter-servlet` implementa `cero.http.Request`/`Response` sobre `HttpServletRequest`/`Response`.
Una aplicación que tenga que seguir viviendo en Tomcat lo hace sin cambiar una línea, mientras el
modo autónomo es el camino nuevo.

Esto es lo que hace reversible la decisión: se puede volver al contenedor sin reescribir nada.

### Metas de la fase

Mismo harness, mismas condiciones para los nueve contendientes. Medido el 24 de septiembre de
2026 ([cómo se mide](../benchmarks/results/LEEME.md)); comparaciones dentro de esa misma corrida,
porque los absolutos dependen de la máquina.

| Métrica | Meta | Medido | Mejor rival, misma corrida | ¿Cumple? |
|---|---|---|---|---|
| Arranque | < 150 ms | **87 ms** | jooby 383 ms | **sí**, y por 4,4× |
| JAR runtime | ≤ 400 KB | 416 KB | — | **no**, por 16 KB |
| Dependencias | 0 | **0** | spring: decenas | **sí** |
| rps `/json` | batir al mejor rival | 25 470 | quarkus 24 981 | **no se puede afirmar** |
| RSS | < 120 MB | 332 MB | vertx 104 MB | **no**, y es el peor de los nueve |

Dos de cinco, y conviene leer las tres que fallan antes que las dos que pasan.

**El RSS es el problema de verdad.** En agosto salió 136,4 MB, el más bajo de la tabla; en
septiembre, 332 MB, el más alto. No cambió el framework tanto como el método: aquella corrida no
acotaba el montón, así que medía lo que el recolector decidía tomar y no lo que el framework
necesita. Con el mismo `-Xmx` para todos, Cero llena su montón y los demás no, porque asigna más
por petición. No es una fuga —el RSS se estanca, y con `-Xmx96m` sirve el mismo tráfico un 4 %
más rápido en 176 MB—, es trabajo pendiente.

**El `/json` ya no se puede reclamar.** Los seis de arriba están dentro del 2 % unos de otros y
el abanico de cada uno entre sus propias cinco repeticiones llega al 12,5 %. La medición no
distingue, así que decir que Cero gana sería ruido.

**El arranque sí.** 87 ms contra 383 del segundo: esa distancia no la explica ningún margen.

Falta repetir la corrida en Linux sin virtualizar: lo de arriba es Docker Desktop. Ver
[docs/mediciones-locales.md](mediciones-locales.md).

## Fase 2 — Cubrir todo lo que ponía el contenedor

Los transversales —métricas, log, tareas, OAuth, contraseñas, caché, eventos, perfiles, OpenAPI—
en los módulos nuevos, hecho. WebSockets propios sobre `cero-http` en lugar de
`jakarta.websocket`, hecho. `cero-view` cubriendo todo lo que hacía JSP en el sitio de referencia,
hecho.

**Criterio de cierre — cumplido el 2 de agosto de 2026.** El sitio de referencia corre entero en
modo autónomo, comprobado por 82 pruebas de punta a punta. Ese sitio vive hoy en su propio
repositorio.

**Cerrada del todo el 4 de agosto de 2026, con la versión 0.3.0.** El 3 de agosto el framework
tuvo su primer consumidor externo —el portal FINESI— y con él la primera auditoría de alguien que
no lo había escrito: once hallazgos leyendo el código, dos de ellos explotables desde fuera sin
credenciales. Están los once cerrados, cada uno con prueba propia, y de ahí salieron además el
almacén de sesiones en tabla, la base opcional de controladores y la confianza en proxy.

Eso es lo que convierte «paridad» en algo comprobado: no la lista de casillas, sino que alguien
de fuera intentara construir encima y anotara lo que le faltaba. Ver
[versiones.md](versiones.md).

## Fase 3 — El framework de frameworks

**Java se termina primero.** Ni el spec ni Rust ni C++ se empiezan hasta que la fase 2 esté
cerrada. Un spec escrito antes de tener una implementación completa describe lo que uno imagina,
no lo que el framework necesita.

`SPEC/cero-kernel.md` define el contrato en lenguaje neutro: modelo de rutas, forma del
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

La corrida de benchmark en Arch bare-metal, el DOI de Zenodo y la autoría con ORCID son trabajo de
publicación y siguen su curso aparte: ver [papers.md](papers.md). No bloquean al framework ni el
framework depende de ellos.
