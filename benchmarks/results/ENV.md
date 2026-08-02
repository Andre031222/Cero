# Entorno del benchmark (para el paper)

- CPU: 12th Gen Intel(R) Core(TM) i5-12500H
- Núcleos: 16 hilos (4 P-cores @2.5GHz + 8 E-cores @1.8GHz)
- RAM: 30Gi
- Kernel: 7.0.14-arch1-1
- JDK (host, LoadClient): openjdk version "25.0.3" 2026-04-21
- Docker: Docker version 29.6.1, build 8900f1d330
- Governor: performance | Turbo: OFF (no_turbo=1)
- Aislamiento: contenedor cpuset=0-3 (2 P-cores, --cpus=4, --memory=2g); cliente taskset=4-7 (2 P-cores)
- Config de carga: conns=64, dur=30s, reps=5 (mediana), warmup=5s, endpoints /plaintext y /json
- Frameworks: jxmvc, spring, quarkus, micronaut, javalin, quarkus-native (GraalVM)
