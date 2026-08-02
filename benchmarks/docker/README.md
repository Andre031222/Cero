# Benchmark dockerizado — un solo comando

Corre el benchmark de forma **aislada y reproducible**: cada framework se construye como una
imagen Docker (misma base `eclipse-temurin:17-jre`, mismos límites de CPU/RAM), se mide y se
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
```
Salida:
- `../results/raw-docker.csv` — cada corrida (framework, imagen, arranque, RSS, endpoint, rps, percentiles).
- `../results/RESULTS-docker.md` — tabla resumida (mediana de rps por framework/endpoint).

## Qué mide (todo bajo idénticas condiciones)
- **Tamaño de imagen** (MB) — artefacto desplegable real, misma base JRE para todos.
- **Arranque en frío** (ms) — desde `docker run` hasta el 1er `200` en `/plaintext`.
- **RSS** (MB) — memoria del contenedor en estado estacionario (`docker stats`).
- **Throughput + latencia** — `LoadClient` desde el host contra el puerto publicado.

## Apps (mismos dos endpoints: `/plaintext` y `/json`)
| Framework | Versión | Runtime |
|---|---|---|
| JxMVC | 3.4.0 | Tomcat 10.1 (WAR, core compilado desde el repo) |
| Spring Boot | 3.3.4 | Tomcat embebido |
| Quarkus | 3.11.3 (JVM) | fast-jar |
| Micronaut | 4.5.3 | Netty |
| Javalin | 6.3.0 | Jetty |

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
