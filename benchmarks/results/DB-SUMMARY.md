# Endpoint /db — resumen para las Tablas 3 y 4 del paper

Run: i5-12500H bare-metal, governor performance + turbo off, contenedor cpuset=0-3
(--cpus=4 --memory=2g), cliente taskset=4-7. conns=64, dur=30s, reps=5 (mediana).
/db = SELECT id,title,body FROM note WHERE id=? sobre H2 in-memory (1000 filas) + JSON.
**Las 3 columnas (plaintext/json/db) provienen del MISMO run → internamente consistentes.**
GraalVM native no se incluye en /db (H2 requiere config de reflexión para native-image).

## Throughput (rps): mediana [min–max]
| Framework | /plaintext | /json | /db | costo /db vs /json |
|---|---|---|---|---|
| jxmvc | 43691 [42396–44008] | 43316 [42551–43673] | 43324 [42755–43594] | -0.0% |
| spring | 44156 [42026–44413] | 44884 [44340–45137] | 42938 [41937–43872] | 4.3% |
| quarkus | 48149 [47081–48591] | 46708 [46338–47657] | 47270 [47074–48068] | -1.2% |
| micronaut | 45338 [44990–46532] | 45196 [43528–45220] | 42715 [42643–43206] | 5.5% |
| javalin | 46202 [45934–47492] | 47668 [47420–47842] | 47442 [47213–47561] | 0.5% |

## Latencia /db (ms): media y p99 (mediana de las reps)
| Framework | mean | p99 |
|---|---|---|
| jxmvc | 1.476 | 3.691 |
| spring | 1.489 | 3.648 |
| quarkus | 1.353 | 3.505 |
| micronaut | 1.497 | 4.915 |
| javalin | 1.347 | 3.476 |
