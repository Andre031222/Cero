# Resultados dockerizados

Generado por `bench.sh` — no editar a mano; se rehace con `./bench.sh --tabla`.

| Parámetro | Valor |
|---|---|
| Límites del contenedor | `--cpus=2 --memory=1g` |
| Montón de la JVM | `-Xmx256m`, idéntico para todos |
| Carga | conns=64, dur=30s, reps=5, warmup=5s |
| Cliente | `LoadClient` desde el host |
| Host | Darwin 25.6.0 arm64 |
| Base JRE | idéntica para todos |
| `/db` | `SELECT` sobre H2 in-memory (1000 filas) + JSON; `Db.java` idéntico en todos salvo la línea `package` |

Arranque, RSS y rps son la **mediana** de las 5 repeticiones. El RSS se mide con el mismo
tope de montón para todos: sin él compara decisiones del recolector, no frameworks. `⚠` = el framework tuvo
errores o respuestas no-2xx: esa fila NO es válida.

> **Aviso.** Esto se midió en Docker Desktop, o sea dentro de una VM y con el cliente de
> carga compartiendo la misma máquina. Los números **relativos** son justos —condiciones
> idénticas para todos—, los **absolutos** no son comparables con una corrida en Linux
> bare-metal y no deben citarse como tales.

| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext (mediana) | rps /json (mediana) | rps /db (mediana) |
|---|---|---|---|---|---|---|
| cero | 112.2 | 87 | 332.1 | 23839.7 | 25470.5 | 24330.4 |
| jooby | 114.6 | 383 | 137 | 24305.5 | 24511.9 | 23554.8 |
| javalin | 115.2 | 457 | 198.2 | 24026.1 | 22748.6 | 24047.9 |
| vertx | 119.6 | 505 | 104.2 | 24728.2 | 24948.7 | 24473.1 |
| helidon | 112.4 | 653 | 115.4 | 24599.0 | 24966.4 | 23545.3 |
| quarkus | 123.2 | 695 | 152.7 | 25327.3 | 24981.3 | 24297.2 |
| micronaut | 120.7 | 907 | 134.6 | 23274.9 | 23630.3 | 20646.0 |
| jxmvc | 110.2 | 979 | 237.1 | 22020.0 | 23549.6 | 21309.7 |
| spring | 127.3 | 1551 | 229.2 | 22353.4 | 23238.1 | 21534.3 |
