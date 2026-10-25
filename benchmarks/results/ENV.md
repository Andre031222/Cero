# Entorno del benchmark

Datos tomados de la máquina donde se ejecutó la corrida del 23 de septiembre de 2026.

> **Este entorno NO cumple el §3 del protocolo.** Es un portátil de trabajo con macOS y Docker
> Desktop: el motor de contenedores corre dentro de una VM, el cliente de carga comparte la
> misma máquina que el servidor y había otros servicios en marcha. Las condiciones son
> idénticas para todos los contendientes —la comparación entre ellos vale—, pero los números
> absolutos no son publicables sin repetir la corrida en Linux bare-metal.

## Host

- CPU: Apple M1 Pro (10 núcleos físicos / 10 hilos), arm64
- RAM: 32 GB
- SO: macOS 26.6.2 (build 25G83), kernel Darwin 25.6.0
- JDK del host (para `LoadClient`): OpenJDK 25.0.3 (Homebrew), 2026-04-21
- Docker: Docker Engine 29.7.2 sobre Docker Desktop
- VM de Docker Desktop: 4 CPUs, 5,78 GiB de RAM
- Aislamiento: **ninguno**. Sin `cpuset`, sin `taskset` (macOS no lo tiene), sin governor de
  CPU. Otros contenedores del usuario seguían en marcha (PostgreSQL, InfluxDB, Grafana,
  Node-RED, Mosquitto).

## Contenedores medidos

- Límites: `--cpus=2 --memory=1g`, idénticos para los nueve
- Base: `eclipse-temurin:21-jre` en siete; `eclipse-temurin:25-jre` en Cero (su build usa
  JDK 25); `tomcat:10.1-jre21` en JxMVC (es un WAR sobre contenedor de servlets)

## Configuración de carga

- `LoadClient` (JDK puro) desde el host contra el puerto publicado
- conns=64, dur=30 s, reps=5 (se reporta la mediana), warmup=5 s
- Endpoints: `/plaintext`, `/json` y `/db` (`BENCH_DB=1`)
- Comando: `cd benchmarks/docker && BENCH_DB=1 ./bench.sh 64 30 5`

## Frameworks de la corrida

cero (0.7.0), spring (3.3.4), quarkus (3.11.3 JVM), micronaut (4.10.16), javalin (6.3.0),
helidon (4.5.5), vertx (5.2.0), jooby (4.5.4), jxmvc (3.4.0). Sin corrida nativa (GraalVM).
