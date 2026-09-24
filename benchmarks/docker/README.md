# Benchmark dockerizado — un solo comando

Corre el benchmark de forma **aislada y reproducible**: cada framework se construye como una
imagen Docker (misma base `eclipse-temurin:21-jre`, mismos límites de CPU/RAM), se mide y se
descarta. El mismo comando produce los mismos resultados en tu laptop o en un Linux nativo.

## Requisitos
- **Docker en marcha** (Docker Desktop iniciado).
- Un JDK en el host (para el generador de carga `LoadClient`).

## Uso
```bash
cd benchmarks/docker
./bench.sh                 # conns=64, dur=20s, reps=3  (por defecto)
./bench.sh 128 30 5        # conns=128, dur=30s, reps=5
BENCH_CPUS=4 BENCH_MEM=2g ./bench.sh
BENCH_PORT=18080 ./bench.sh    # si el 8080 está ocupado en tu máquina
```

Variables: `BENCH_CPUS`, `BENCH_MEM`, `BENCH_PORT`, `BENCH_CPUSET` (fija el contenedor a unos
núcleos), `BENCH_CLIENT_CPUS` (fija el cliente a otros, **solo Linux**: usa `taskset`),
`BENCH_DB=1` (añade `/db`) y `BENCH_NATIVE=1` (añade Quarkus nativo).
Salida:
- `../results/raw-docker.csv` — cada corrida (framework, imagen, arranque, RSS, endpoint, rps, percentiles).
- `../results/RESULTS-docker.md` — tabla resumida (mediana de rps por framework/endpoint).

## Qué mide (todo bajo idénticas condiciones)
- **Tamaño de imagen** (MB) — artefacto desplegable real, misma base JRE para todos.
- **Arranque en frío** (ms) — desde `docker run` hasta el 1er `200` en `/plaintext`.
- **RSS** (MB) — memoria del contenedor en estado estacionario (`docker stats`).
- **Throughput + latencia** — `LoadClient` desde el host contra el puerto publicado.

## Apps (mismos endpoints: `/plaintext`, `/json` y, con `BENCH_DB=1`, `/db`)
| Framework | Versión | Runtime |
|---|---|---|
| **Cero** | 0.7.0 | servidor propio, un hilo virtual por conexión |
| Spring Boot | 3.3.4 | Tomcat embebido |
| Quarkus | 3.11.3 (JVM) | fast-jar |
| Micronaut | 4.10.16 | Netty |
| Javalin | 6.3.0 | Jetty |
| Helidon SE | 4.5.5 | Helidon Níma (hilos virtuales) |
| Vert.x | 5.2.0 (+ `vertx-web`) | Netty, event loop |
| Jooby | 4.5.4 | Netty |
| JxMVC | 3.4.0 | WAR sobre Tomcat 10.1 (servlets) |

Entre Spark Java y Jooby se eligió **Jooby**: Spark Java sigue en 2.9.4 (julio de 2022) y no
tiene versiones nuevas, mientras que Jooby publica releases de forma habitual.

Dos notas de paridad para los nuevos:

- **Vert.x** sirve `/db` con `blockingHandler` (pool de workers). JDBC es bloqueante y ponerlo
  en el event loop no sería el uso idiomático del framework; `/plaintext` y `/json` sí van en
  el event loop.
- **Jooby** devuelve `text/plain;charset=utf-8` en `/plaintext`; el cuerpo es el mismo `OK`.
- **JxMVC** no se compila desde el código fuente —no está en este repositorio—: el Dockerfile
  instala con `mvn install:install-file` el artefacto publicado `jxmvc-core-3.4.0` que hay en
  `apps/jxmvc/lib/`. Su `/plaintext` devuelve `text/plain;charset=UTF-8`.

Las imágenes comparten base `eclipse-temurin:21-jre`, con dos excepciones: Cero corre sobre
`eclipse-temurin:25-jre` (su build usa JDK 25) y JxMVC sobre `tomcat:10.1-jre21`, porque se
despliega como WAR en un contenedor de servlets. Son diferencias reales de la corrida y
conviene tenerlas presentes al leer arranque y RSS.

La app de Cero toma la versión del framework de `java/pom.xml` en tiempo de build, no de un
número escrito a mano: fijarla fue justo lo que dejó a Cero fuera de la tabla durante días.

`bench.sh` es **resiliente**: si una imagen no construye o no arranca, lo registra (con sus logs)
y continúa con las demás.

## Honestidad / validez
- En Docker Desktop (Windows/macOS) el motor corre en una VM: los números **relativos** son
  justos (idénticas condiciones), los **absolutos** difieren de bare-metal. Para cifras finales
  de publicación, correr este mismo `bench.sh` en un **Linux nativo** (p. ej. el VPS Debian).
- Quarkus/Micronaut brillan de verdad en **modo nativo (GraalVM)**: añadir una corrida nativa
  aparte para no subrepresentarlos.
- El tamaño de imagen incluye el JRE base (igual para todos), así que compara el **desplegable
  completo** — más justo que "tamaño del framework solo".
