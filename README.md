# LuxCore

Núcleo de framework web sin dependencias, que arranca solo y está pensado para vivir en más de
un lenguaje.

[![Licencia: MIT](https://img.shields.io/badge/Licencia-MIT-15803d?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-007396?style=flat-square)](https://openjdk.org/)
[![Dependencias](https://img.shields.io/badge/Dependencias-0-2e7d32?style=flat-square)](#principios)
[![Estado](https://img.shields.io/badge/Estado-fase%200-8E8E93?style=flat-square)](#estado)

* * *

## Qué es

LuxCore nace de [JxMVC 3.4.0](ORIGEN.md), un framework MVC en Java con cero dependencias que
funciona y está en producción — pero que necesita Tomcat para arrancar y solo existe para Java.

LuxCore cambia esas dos cosas:

1. **Arranca solo.** Servidor HTTP propio sobre `java.nio` con hilos virtuales. `java -jar app.jar`
   y está corriendo. Sin contenedor de servlets, sin `web.xml`, sin despliegue.
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

**`lux-http` completo.** El servidor HTTP/1.1 propio está terminado: 31 clases, 144 pruebas en
verde, cero dependencias, ni una línea de Jakarta en todo el módulo. Ya no necesita nada de lo que
antes ponía el contenedor de servlets.

```java
Server.start(8080, (req, res) -> res.text("Hello, World!"));
```

Cubre: keep-alive, chunked en ambos sentidos, `Expect: 100-continue`, HEAD, streaming,
**TLS**, **cookies**, **sesiones**, **multipart**, **gzip** y **archivos estáticos**, con techo de
conexiones, watchdog de handler y apagado ordenado.

Medición local ([detalle y salvedades](docs/mediciones-locales.md)):

| | JxMVC sobre Tomcat | lux-http | Meta de fase 1 |
|---|---|---|---|
| Arranque | 1392 ms | **28 ms** | < 150 ms |
| JAR | 253 KB + ~15 MB Tomcat | **64 KB** | ≤ 400 KB |
| rps `/plaintext` | 43 691 | 100 554 ⚠ | ≥ 48 000 |
| Pruebas | 347 | **144** | — |
| Dependencias | 0 (+ Jakarta *provided*) | **0** | 0 |

⚠ El rps se midió con cliente y servidor en la misma máquina, y **no es comparable** con la tabla
del paper. El número que cuenta sale del harness de `benchmarks/docker` en Arch bare-metal.
Arranque y tamaño del JAR sí son sólidos.

Lo siguiente es `lux-core`: desacoplar `JxRequest`/`JxResponse` de Jakarta y partir las 1041 líneas
de `MainLxServlet` en un `LuxDispatcher` legible.

## Estructura

```text
java/
  lux-http/      Servidor HTTP/1.1 propio, TLS, sesiones, estáticos — hecho
  lux-core/      Router, pipeline, DI, configuración                  (siguiente)
  lux-view/      Motor de plantillas, sustituye JSP                   (fase 2)
  lux-data/      Acceso a datos, migrado desde legacy                 (fase 2)
  lux-launcher/  Fat-jar, java -jar app.jar                           (fase 1)
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
cd java && mvn test          # lux-http: 144 pruebas, runner propio
cd java && mvn package       # lux-http/target/lux-http-0.1.0.jar
```

El núcleo heredado sigue compilando aparte, y todavía necesita Tomcat para `jxmvc2x`:

```bash
cd legacy/jxmvc-core && mvn test    # 347 pruebas
```

## Documentos

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
