# Resultados del benchmark — corrida OFICIAL (bare-metal)

Cifras oficiales para el paper. Fuente auto-generada: [`RESULTS-docker.md`](RESULTS-docker.md)
(tabla), [`STATS.md`](STATS.md) (mediana + [min–max]), [`ENV.md`](ENV.md) (entorno),
[`raw-docker.csv`](raw-docker.csv) (60 filas crudas). Metodología: [`../README.md`](../README.md).

> Una corrida preliminar previa en Docker Desktop (WSL2, `--cpus=2`) quedó en el historial de git
> (commit `e8d49ac`); **esta corrida bare-metal la reemplaza** para efectos de publicación.

## Entorno

```
CPU:        12th Gen Intel Core i5-12500H (4 P-cores @2.5GHz + 8 E-cores @1.8GHz, 16 hilos)
RAM:        30 GiB · Kernel 7.0.14-arch1-1 (Arch) · JDK 25 (host) · Docker 29.6.1
Aislamiento: contenedor --cpuset-cpus=0-3 --cpus=4 --memory=2g · cliente taskset 4-7 (disjuntos)
CPU:        governor performance · Turbo OFF · carga conns=64 dur=30s reps=5 (mediana) warmup=5s
Endpoints:  /plaintext y /json idénticos en los 6 · misma base JRE
```

## Tabla (mediana de 5 repeticiones)

| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext | rps /json | errores |
|---|---|---|---|---|---|---|
| **JxMVC 3.4.0** | **271.7** | 822 | 448.5 | 49 062 | 48 720 | **0** |
| Spring Boot 3.3 | 299.9 | 1945 | 375.6 | 49 462 | 49 896 | 0 |
| Quarkus 3.11 (JVM) | 295.9 | 606 | 431.2 | 55 087 | 54 209 | 0 |
| Micronaut 4.10 | 292.5 | 1154 | 331.4 | 50 974 | 50 652 | 0 |
| Javalin 6 (Jetty) | 286.4 | 369 | 424.6 | 54 169 | 53 614 | 0 |
| **Quarkus nativo (GraalVM)** | **72.8** | **12** | **25.1** | 52 699 | 50 073 | 0 |

Dispersión (min–max de rps) en [`STATS.md`](STATS.md). Volumen total: **92.76M peticiones,
0 errores y 0 respuestas no-2xx** en 60 corridas (6 frameworks × 2 endpoints × 5 reps).

## Lectura honesta

**Fortalezas medidas de JxMVC:**
- **Imagen JVM más pequeña** (271.7 MB) de las cinco.
- **Arranque 822 ms** — más rápido que **Micronaut** (1154) y **Spring** (1945); detrás de
  Quarkus (606) y Javalin (369).
- **Latencia p99 indistinguible** del resto bajo la misma carga.
- **0 errores** en 92.76M peticiones.

**Debilidad honesta:**
- **RSS 448 MB — el más alto** de los JVM (coste del contenedor servlet Tomcat).
- **Throughput 49k rps — el más bajo** de los JVM, aunque **~par con Spring** (~1% por debajo) y
  a ~11% del líder (Quarkus). El claim previo de "por encima de Spring/Micronaut" **no se sostuvo**
  en bare-metal y fue corregido en el paper.

**GraalVM nativo (Quarkus):** confirma la ventaja del AOT — **arranque 12 ms** y **RSS 25 MB**
(vs cientos en JVM), con throughput a la par. Es el argumento de por qué el modo nativo va en fila
aparte y no debe compararse de igual a igual con los JVM.

**Conclusión defendible:** para un framework de **253 KB y cero dependencias**, JxMVC es
**competitivo** — imagen JVM mínima, arranque mejor que Spring/Micronaut, latencia y corrección
a la par — con **mayor memoria** como contrapartida clara del stack servlet. Historia creíble y
honesta, no "el más rápido".
