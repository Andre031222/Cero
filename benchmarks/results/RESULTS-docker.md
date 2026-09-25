# Resultados dockerizados

Generado por `bench.sh` — no editar a mano; se rehace con `./bench.sh --tabla`.

| Parámetro | Valor |
|---|---|
| Límites del contenedor | `--cpus=2 --memory=1g` `--cpuset-cpus=0,1` |
| Montón de la JVM | `-Xmx256m`, idéntico para todos |
| Carga | conns=64, dur=30s, reps=5, warmup=5s |
| Cliente | `LoadClient` desde el host, fijado a los núcleos 4-15 (aislado) |
| Host | Linux 7.2.3-arch1-2 x86_64 |
| Base JRE | idéntica para todos |
| `/db` | `SELECT` sobre H2 in-memory (1000 filas) + JSON; `Db.java` idéntico en todos salvo la línea `package` |

Arranque, RSS y rps son la **mediana** de las 5 repeticiones. El RSS se mide con el mismo
tope de montón para todos: sin él compara decisiones del recolector, no frameworks. `⚠` = el framework tuvo
errores o respuestas no-2xx: esa fila NO es válida.

| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext (mediana) | rps /json (mediana) | rps /db (mediana) |
|---|---|---|---|---|---|---|
| cero | 323.2 | 155 | 307.9 | 93576.7 | 94634.1 | 86689.7 |
| jooby | 310.8 | 443 | 73.42 | 94111.7 | 93280.6 | 84429.1 |
| vertx | 316.3 | 481 | 79.49 | 99241.1 | 98589.2 | 88367.5 |
| javalin | 311.4 | 518 | 117.4 | 89193.0 | 88772.6 | 68686.1 |
| helidon | 308.4 | 583 | 83.27 | 90078.2 | 92693.1 | 79402.1 |
| quarkus | 321.0 | 711 | 104.6 | 93409.0 | 91822.8 | 65069.3 |
| micronaut | 317.6 | 1102 | 104.7 | 76941.1 | 76464.6 | 64750.8 |
| spring | 325.0 | 2446 | 176.5 | 60903.3 | 64132.7 | 49869.5 |
| jxmvc | (no se midió: build o arranque falló) | | | | | |
