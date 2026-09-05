# Arquitectura de Cero

Este documento describe hacia dónde va el código. **Las fases 1 y 2 están cerradas**: siete
módulos, el sitio de referencia sobre el propio framework y 1 227 pruebas. Lo que sigue es la
fase 3. Ver [origen.md](origen.md) para de dónde viene todo.

## El hallazgo que define el plan

Antes de diseñar nada se midió el acoplamiento real del núcleo heredado al contenedor de servlets.
El resultado es mejor de lo que sugiere el README de JxMVC:

- De **54 clases** en el núcleo de JxMVC 3.4.0, **40 no importan nada de `jakarta.*`**.
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
  cero-http/               Servidor HTTP/1.1 propio (hilos virtuales). Cero deps.
  cero-core/               Router, pipeline, DI, configuración, resultados. Cero deps.
  cero-view/               Motor de plantillas propio (sustituye a JSP)
  cero-data/               JxDB, JxRepository, JxPool, JxTransaction — se mudan casi tal cual
  cero-adapter-servlet/    Compatibilidad Jakarta/Tomcat para las apps AUR ya desplegadas
  cero-web/                Sitio oficial: acceso, demos y generador de proyectos
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
- **Métodos cortos.** Uno hace una cosa. `MainLxServlet` con 1046 líneas es el contraejemplo que
  motiva esta regla.
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

`JxTagFor` y `JxTagIf` dependen de `jakarta.servlet.jsp` y se eliminan. Los sustituye `cero-view`:
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

Imprime host, puerto, número de rutas y tiempo de arranque. `cero-launcher` como módulo aparte
—empaquetado fat-jar— queda para la fase 2; para arrancar ya no hace falta.

### 1.6 No romper lo que ya está en producción

`cero-adapter-servlet` implementa `cero.http.Request`/`Response` sobre `HttpServletRequest`/`Response`.
Las apps AUR que hoy dependen de JxMVC (Academia, Intranet, NFC Intranet) siguen desplegando en
Tomcat sin cambiar una línea, mientras el modo standalone es el camino nuevo.

Esto es lo que convierte la refundación en una migración y no en una ruptura.

### Metas de la fase

Mismo harness, mismas condiciones para los seis contendientes. Medido el 2 de agosto de 2026
([tabla](../benchmarks/results/RESULTS-docker.md)); comparaciones dentro de esa misma corrida,
porque los absolutos dependen de la máquina.

| Métrica | Meta | Medido | Mejor rival, misma corrida | ¿Cumple? |
|---|---|---|---|---|
| Arranque | < 150 ms | **106 ms** | javalin 451 ms | **sí**, y por 4,3× |
| JAR runtime | ≤ 400 KB | **407 KB** | — | **sí** |
| Dependencias | 0 | **0** | spring: decenas | **sí** |
| rps `/json` | batir al mejor rival | **25 431** | javalin 25 125 | **sí** |
| RSS | < 120 MB | 136,4 MB | micronaut 201,2 MB | **no**, aunque es el más bajo |

Cuatro de cinco, y la que falla lo hace por poco: 136,4 MB contra una meta de 120. Esa métrica
era el punto flojo del proyecto —298 MB esta mañana— hasta que se vio que no era peso sino un
fallo: el vigilante programaba una tarea por petición y cancelarla no la sacaba de la cola.

Falta repetir la corrida en el mismo Arch bare-metal que usó el paper: lo de arriba es Docker
Desktop. Ver [docs/mediciones-locales.md](mediciones-locales.md).

## Fase 2 — Paridad

Migrar las 41 clases puras a los módulos nuevos — hecho, las 14 transversales incluidas.
WebSockets propios sobre `cero-http` sustituyendo `jakarta.websocket` — hecho. `cero-view`
cubriendo todo lo que hacía JSP en el sitio de referencia — hecho.

De las 347 pruebas heredadas no se hizo un port literal: se compararon una a una contra las
nuestras buscando comportamiento sin cubrir, que es lo que aportaban. Aparecieron dos fallos
reales —una redirección abierta y una carrera que hacía al pool pasarse de su tope— y los dos
están corregidos con prueba propia.

**Criterio de cierre — cumplido el 2 de agosto de 2026.** El sitio de referencia corre entero en
modo standalone: es `java/cero-web`, con 82 pruebas de punta a punta que lo comprueban. Sustituye
al `jxmvc2x` heredado, que se ha retirado del repositorio.

**Cerrada del todo el 4 de agosto de 2026, con la versión 0.3.0.** El 3 de agosto el framework
tuvo su primer consumidor externo —el portal FINESI— y con él la primera auditoría de alguien que
no lo había escrito: once hallazgos leyendo el código, dos de ellos explotables desde fuera sin
credenciales. Están los once cerrados, cada uno con prueba propia, y de ahí salieron además el
almacén de sesiones en tabla, la base opcional de controladores y la confianza en proxy.

Eso es lo que convierte «paridad» en algo comprobado: no la lista de casillas, sino que alguien
de fuera intentara construir encima y anotara lo que le faltaba. Ver
[versiones.md](versiones.md).

Lo que **no** se hizo, y a propósito: portar literalmente las 347 pruebas heredadas. Se comparó
cobertura una a una, que es lo que aportaban, y aparecieron dos fallos reales.

## Fase 3 — El framework de frameworks

**Java se termina primero.** Ni el spec ni Rust ni C++ se empiezan hasta que la fase 2 esté
cerrada. Un spec escrito antes de tener una implementación completa describe lo que uno imagina,
no lo que el framework necesita.

Aquí Cero deja de ser «JxMVC v4».

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

`19.Soft_JXMVC` y `AUP_Papers/13.-JxMVC_SPE/` no se tocan. Los pendientes del artículo (benchmark
`/db` en Arch, DOI de Zenodo, autoría y ORCID) siguen su curso aparte; Cero no los bloquea ni
depende de ellos.
