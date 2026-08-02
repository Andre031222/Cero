# LuxCore

Núcleo de framework web sin dependencias, que arranca solo y está pensado para vivir en más de
un lenguaje.

[![Licencia: MIT](https://img.shields.io/badge/Licencia-MIT-15803d?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-007396?style=flat-square)](https://openjdk.org/)
[![Dependencias](https://img.shields.io/badge/Dependencias-0-2e7d32?style=flat-square)](#principios)
[![Pruebas](https://img.shields.io/badge/Pruebas-601-15803d?style=flat-square)](#estado)

* * *

## Qué es

LuxCore nace de [JxMVC 3.4.0](ORIGEN.md), un framework MVC en Java con cero dependencias que
funciona y está en producción — pero que necesita Tomcat para arrancar y solo existe para Java.

LuxCore cambia esas dos cosas:

1. **Arranca solo.** Servidor HTTP propio con un hilo virtual por conexión. `java -jar app.jar` y
   está corriendo. Sin contenedor de servlets, sin `web.xml`, sin despliegue.
2. **No es solo Java.** El objetivo final no es un framework más: es un contrato de framework
   —rutas, pipeline, request/response, DI, configuración— definido de forma neutral e
   **implementado en Java, Rust y C++**, con un mismo banco de pruebas de conformidad para los tres.

Lo que no cambia es la identidad del proyecto: cero dependencias externas, poco peso, y un núcleo
que un desarrollador puede leer entero.

## Principios

- **Cero dependencias en runtime.** Solo el JDK. El driver JDBC de tu base de datos es la única
  excepción, y la pone la aplicación.
- **Legible antes que ingenioso.** Si una clase no se puede leer de corrido, se parte.
- **Medido, no proclamado.** Toda afirmación de rendimiento sale de `benchmarks/`, con el mismo
  harness que ya compara contra Spring, Quarkus, Micronaut y Javalin.
- **El spec manda.** Desde la fase 3, ninguna implementación es la de referencia: la referencia es
  `SPEC/`, y las tres pasan las mismas pruebas.

## Estado

**El framework ya corre solo.** Cuatro módulos terminados —servidor, núcleo, vistas y datos—:
102 clases, **601 pruebas en verde**, cero dependencias, y ni una sola referencia a `jakarta.*`
en todo `java/`.

```java
@Route("/api")
class ApiController {

    @Inject Catalogo catalogo;

    @Get("/articulos/{id}")
    public Object porId(@Path("id") int id) {
        return catalogo.porId(id);
    }
}

Lux.run(8080, ApiController.class);
```

Eso arranca un servidor con ruteo, inyección de dependencias, serialización JSON y manejo de
errores en **51 ms**, sin contenedor y sin una sola dependencia externa.

Medición local ([detalle y salvedades](docs/mediciones-locales.md)):

| | JxMVC sobre Tomcat | LuxCore | Meta de fase 1 |
|---|---|---|---|
| Arranque | 1392 ms | **51 ms** | < 150 ms |
| Artefacto | 253 KB + ~15 MB Tomcat | **215 KB** (4 JAR) | ≤ 400 KB |
| rps | 43 691 | 94 610 ⚠ | ≥ 48 000 |
| Pruebas | 347 | **601** | — |
| Dependencias | 0 (+ Jakarta *provided*) | **0** | 0 |

⚠ El rps se midió con cliente y servidor en la misma máquina, y **no es comparable** con la tabla
del paper. El número que cuenta sale del harness de `benchmarks/docker` en Arch bare-metal.
Arranque y tamaño del artefacto sí son sólidos.

## Qué trae cada módulo

**`lux-http`** (144 pruebas) — servidor HTTP/1.1 con un hilo virtual por conexión: keep-alive,
chunked en ambos sentidos, `Expect: 100-continue`, HEAD, streaming, TLS, cookies, sesiones,
multipart, gzip y archivos estáticos. Techo de conexiones, watchdog de handler y apagado ordenado.

**`lux-core`** (228 pruebas) — el framework: router con plantillas `{var}` y comodines, pipeline
con middleware, inyección de dependencias con detección de ciclos, JSON propio (escritura, lectura
y vinculación a records), vinculación de parámetros (`@Path`, `@Query`, `@Body`, `@Header`,
`@CookieValue`), autenticación y roles (`@RequireAuth`, `@RequireRole`), manejo de errores
(`@OnError`) y configuración por properties, entorno y propiedades del sistema.

Trae además los transversales de una app real: **CORS** con preflight, **CSRF** con token en
sesión y comparación en tiempo constante, **rate limiting** por ventana deslizante, **validación**
declarativa (`@Required`, `@Length`, `@Range`, `@Email`, `@Match`, `@OneOf`, `@Satisfies`) que
responde 422 con el mapa de campos, y **sanitizado** de HTML y nombres de archivo.

**`lux-view`** (88 pruebas) — motor de plantillas propio, sustituye a JSP. `{{ expr }}` escapado
por defecto, `{% if %}`, `{% for %}` con `loop.index`/`first`/`last`, `{% include %}` y herencia
con `{% extends %}` y `{% block %}`. Plantillas compiladas y cacheadas.

**`lux-data`** (141 pruebas) — `Row`, `Db`, `Pool`, `Tx` y `Repository<T, ID>` sobre `@Table`,
`@Id` y `@Column`. Todo por `PreparedStatement`; los identificadores se validan carácter a
carácter y los valores nunca se concatenan.

## Qué falta

Del núcleo heredado quedan **14 clases y 2 566 líneas** por migrar, ninguna acoplada a Jakarta:
cache, scheduler, métricas, eventos, logger, perfiles, OpenAPI, OAuth, contraseñas y WebSocket.

## Estructura

```text
java/
  lux-http/      Servidor HTTP/1.1 propio, TLS, sesiones, estáticos — hecho
  lux-core/      Router, pipeline, DI, JSON, configuración          — hecho
  lux-view/      Motor de plantillas, sustituye JSP                 — hecho
  lux-data/      Acceso a datos: Db, Pool, Tx, Repository           — hecho
  lux-launcher/  Fat-jar, java -jar app.jar                           (pendiente)
legacy/
  jxmvc-core/    Núcleo heredado — origen de la migración
  jxmvc2x/       Web de referencia en JSP — banco de pruebas de lux-view
spec/            Contrato del kernel, neutral respecto al lenguaje    (fase 3)
rust/  cpp/      Implementaciones adicionales                         (fase 3)
benchmarks/      Harness comparativo con Spring, Quarkus, Micronaut, Javalin
docs/            Documentación
```

## Compilar

LuxCore necesita Java 21+ (hilos virtuales) y Maven. Nada más.

```bash
cd java && mvn test          # 601 pruebas, runner propio (sin JUnit)
cd java && mvn package       # cada módulo en su target/
```

El núcleo heredado sigue compilando aparte, y todavía necesita Tomcat para `jxmvc2x`:

```bash
cd legacy/jxmvc-core && mvn test    # 347 pruebas
```

## Web del proyecto

Sitio de siete páginas en `docs/web/`, sin dependencias ni paso de compilación de terceros:
HTML, una hoja de estilo y dos guiones. Nada de npm.

| Página | Qué es |
|---|---|
| [index.html](docs/web/index.html) | Portada: qué es, un vistazo al código y por dónde seguir |
| [empezar.html](docs/web/empezar.html) | Instalación con guía en terminal animada, bilingüe ES/EN |
| [guia.html](docs/web/guia.html) | Rutas, parámetros, respuestas, inyección, validación y errores |
| [modulos.html](docs/web/modulos.html) | Servidor, vistas, datos, seguridad y configuración |
| [referencia.html](docs/web/referencia.html) | Motores verificados, sistemas y comparación con Tomcat |
| [estado.html](docs/web/estado.html) | Qué está probado y qué falta para producción |
| [marca/](docs/web/marca/) | El logo: construcción, tamaños, uso y `logo.svg` suelto |

Las páginas se generan con `./lux sitio`, que envuelve los fragmentos de `docs/web/contenido/`
con la cabecera, la navegación y el pie. Así la navegación se escribe una sola vez.

El mismo generador produce `docs/web/completo.html`: el sitio entero en **un archivo de 126 KB**,
sin recursos externos. Sirve para leerlo sin conexión, mandarlo por correo o abrirlo con doble
clic — y es una demostración de lo que el proyecto defiende.

Para verlo servido por el propio LuxCore:

```bash
./lux web        # http://localhost:8095
```

Se puede servir con el propio LuxCore:

```java
Lux.app().port(8080)
   .fallback(StaticFiles.from(Path.of("docs/web")))
   .start();
```

## Documentos

- [docs/produccion.md](docs/produccion.md) — **¿está listo para producción?** (respuesta corta: no
  todavía, y ahí está la lista concreta de qué falta)
- [docs/auditoria-2026-08-01.md](docs/auditoria-2026-08-01.md) — comparación con JxMVC, capa por capa
- [ARQUITECTURA.md](ARQUITECTURA.md) — el diseño y las tres fases
- [ORIGEN.md](ORIGEN.md) — de dónde viene el código y por qué el original no se toca
- [AUTORES.md](AUTORES.md) — autoría y atribución del código heredado

## Licencia

MIT — ver [LICENSE](LICENSE) y [NOTICE](NOTICE).

Autor: **Richar Andre Vilca-Solorzano**. Universidad Nacional del Altiplano, Puno, Perú.

```bibtex
@software{vilcasolorzano2026luxcore,
  title  = {LuxCore: núcleo de framework web poliglota sin dependencias},
  author = {Vilca-Solorzano, Richar Andre},
  year   = {2026},
  url    = {https://github.com/Andre031222/45.Soft_LuxCore}
}
```
