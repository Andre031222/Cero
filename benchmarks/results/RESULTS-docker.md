# Resultados dockerizados (con endpoint /db)

Entorno: `docker --cpus=4 --memory=2g --cpuset-cpus=0-3`, misma base JRE. conns=64, dur=30s, reps=5. Cliente aislado (taskset 4-7).
Arranque/RSS/rps = **mediana** de 5 reps. `/db` = SELECT sobre H2 in-memory (1000 filas) + JSON. `⚠` = errores/no-2xx.

| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext | rps /json | rps /db |
|---|---|---|---|---|---|---|
| jxmvc | 274.1 | 1392 | 471.8 | 43690.8 | 43315.5 | 43324.1 |
| spring | 302.4 | 2582 | 432.8 | 44155.8 | 44884.2 | 42938.2 |
| quarkus | 298.4 | 878 | 411.5 | 48148.9 | 46708.4 | 47270.4 |
| micronaut | 295.0 | 1157 | 331.7 | 45338.3 | 45195.8 | 42715.1 |
| javalin | 288.9 | 466 | 471.2 | 46201.7 | 47667.5 | 47441.8 |
