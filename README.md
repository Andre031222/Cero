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

**Fase 0 — línea base heredada.** El árbol es todavía el de JxMVC 3.4.0: `JxMVC.Core/` (el
framework), `JxMVC2x/` (web de referencia en JSP) y `benchmarks/` (harness dockerizado). La
arquitectura nueva está descrita en [ARQUITECTURA.md](ARQUITECTURA.md) y aún no implementada.

Punto de partida, medido en Docker con 4 cpus y 2 GB:

| | JxMVC hoy | Meta LuxCore | A batir |
|---|---|---|---|
| Arranque | 1392 ms | < 150 ms | javalin 466 ms |
| RSS | 471.8 MB | < 120 MB | micronaut 331.7 MB |
| rps `/json` | 43 315 | ≥ 48 000 | javalin 47 667 |
| JAR runtime | 253 KB | ≤ 400 KB | — |

De los 1392 ms de arranque, la mayor parte es Tomcat levantándose. Ese es el primer objetivo.

## Estructura

```text
SPEC/          Contrato del kernel, neutral respecto al lenguaje  (fase 3)
java/          Implementación de referencia en Java               (fase 1)
rust/  cpp/    Implementaciones adicionales                       (fase 3)
benchmarks/    Harness comparativo con Spring, Quarkus, Micronaut, Javalin
docs/          Documentación
JxMVC.Core/    Núcleo heredado — origen de la migración
JxMVC2x/       Web de referencia heredada (JSP) — banco de pruebas del motor de vistas
```

## Compilar (estado heredado)

Requisitos actuales: Java 17+ y Maven. Tomcat 10.1+ solo para desplegar `JxMVC2x`.

```bash
cd JxMVC.Core && mvn install    # framework
cd JxMVC.Core && mvn test       # 347 pruebas, runner propio (sin JUnit)
```

A partir de la fase 1 el requisito pasa a Java 21+ (hilos virtuales) y Tomcat deja de hacer falta.

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
