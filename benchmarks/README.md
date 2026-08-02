# JxMVC — Harness de benchmarks reproducible

Protocolo y herramientas para medir **JxMVC** frente a **Spring Boot, Quarkus, Micronaut y
Javalin** de forma reproducible. Diseñado para respaldar las cifras de un paper (tamaño,
arranque, memoria, throughput y latencia) con una metodología descrita y sin herramientas
externas de carga (el generador está en JDK puro).

> Estado: harness completo y ejecutable. Las filas de resultados se completan ejecutando el
> protocolo en una **máquina limpia y aislada** (ver §6). No publiques números de una laptop
> de desarrollo con IDE/navegador abiertos.

---

## 1. Qué se mide (métricas)

| Métrica | Definición | Cómo |
|---|---|---|
| **Tamaño desplegable** | Bytes del artefacto que se despliega, **incluyendo el servidor** | `scripts/measure-size.sh` |
| **Dependencias runtime** | Nº de JARs de terceros en el classpath de ejecución | conteo del árbol de dependencias |
| **Arranque en frío** | ms desde lanzar el proceso hasta la 1ª respuesta 200 | `scripts/measure-startup.sh` |
| **Memoria (RSS)** | RSS tras warmup en estado estacionario | `scripts/measure-memory.sh` |
| **Throughput** | req/s sostenidos, ventana de medición tras warmup | `load/LoadClient` |
| **Latencia** | media y p50/p90/p95/p99 (ms) bajo la misma carga | `load/LoadClient` |

Dos endpoints canónicos, iguales en todos los frameworks:
- `GET /plaintext` → `text/plain` con el cuerpo `OK`.
- `GET /json` → `application/json` con `{"message":"hello","n":42}`.

## 2. Regla de honestidad sobre el tamaño

JxMVC empaqueta **solo el framework** (~253 KB); el servlet container (Tomcat/Jakarta EE) es
`provided` y NO va en el artefacto. Spring Boot/Quarkus/Micronaut producen *uber-JARs* que
**incluyen** su servidor embebido. Por eso el tamaño se reporta de dos formas:

1. **Framework solo** (JAR del framework sin servidor) — favorece a JxMVC, se marca como tal.
2. **Desplegable + servidor** (JxMVC core + `tomcat-embed`/Tomcat mínimo vs el uber-JAR del rival) —
   la comparación **justa** y la que va en las conclusiones del paper.

## 3. Entorno (rellenar al ejecutar)

```
CPU:            <modelo, núcleos/hilos>
RAM:            <GB>
SO:             <distro/versión, kernel>
JDK:            <vendor + versión>   (mismo para todos)
Aislamiento:    sin GUI, sin otros servicios; governor=performance
Cliente/carga:  misma máquina (loopback) o máquina dedicada en LAN (preferido)
Repeticiones:   N=5 corridas por métrica; se reporta mediana + [min,max]
Warmup:         5 s (carga) / descartar 1ª corrida (arranque)
```

## 4. Herramienta de carga (JDK puro, sin dependencias)

```bash
cd load && javac LoadClient.java
java LoadClient <url> <conexiones> <segundos> [warmupSegs]
# ej:
java LoadClient http://localhost:8080/plaintext 64 30 5
```
Imprime una línea CSV: `url,conns,durSecs,requests,errors,non2xx,rps,meanMs,p50,p90,p95,p99`.

## 5. Apps de referencia

Las apps mínimas equivalentes (mismos dos endpoints) y sus comandos exactos de build/run están
en [`apps/APPS.md`](apps/APPS.md), con versiones fijadas. Todas exponen `/plaintext` y `/json`
para que la comparación sea uno-a-uno.

## 6. Protocolo de ejecución

```bash
# 1) Levantar UNA app (en su propia terminal), esperar "listening"
# 2) Medir arranque y memoria del proceso lanzado
scripts/measure-startup.sh "<cmd de arranque>" http://localhost:PORT/plaintext
scripts/measure-memory.sh  <pid>
# 3) Medir throughput/latencia (repetir N=5, tomar mediana)
scripts/run-load.sh http://localhost:PORT 64 30
# 4) Medir tamaño desplegable
scripts/measure-size.sh
# 5) Repetir 1-4 por cada framework; agregar en results/RESULTS.md
```

Barridos recomendados de concurrencia: `1, 8, 32, 64, 128, 256` conexiones (curva de
throughput/latencia), 30 s por punto, 5 s de warmup.

## 7. Resultados

Se consolidan en [`results/RESULTS.md`](results/RESULTS.md) (tabla + CSV crudo por corrida).
Las filas vienen pre-cargadas con los datos ya conocidos y verificables de **tamaño y
dependencias** de JxMVC; el resto se completa al correr el protocolo.

## 8. Amenazas a la validez (para el paper)

- **Steady-state del JIT**: sin warmup, HotSpot penaliza a la JVM; por eso se descarta el warmup.
- **Comparación de tamaño**: ver §2 — reportar ambas formas evita la crítica "es tramposo".
- **Carga en loopback**: satura el mismo host; para números finales, cliente en máquina aparte.
- **AOT vs JIT**: Quarkus/Micronaut pueden compilarse a nativo (GraalVM), lo que cambia
  radicalmente arranque/memoria; indicar el modo (JVM vs nativo) de cada corrida.
- **Paridad de endpoints**: los dos endpoints deben ser idénticos en semántica y salida.
