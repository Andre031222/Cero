# Resultados dockerizados

Generado por `bench.sh` — no editar a mano; se rehace con `./bench.sh --tabla`.

| Parámetro | Valor |
|---|---|
| Límites del contenedor | `--cpus=2 --memory=1g` |
| Carga | conns=64, dur=30s, reps=5, warmup=5s |
| Cliente | `LoadClient` desde el host |
| Host | Darwin 25.6.0 arm64 |
| Base JRE | idéntica para todos |
| `/db` | `SELECT` sobre H2 in-memory (1000 filas) + JSON; `Db.java` byte-idéntico en todos |

Arranque, RSS y rps son la **mediana** de las 5 repeticiones. `⚠` = el framework tuvo
errores o respuestas no-2xx: esa fila NO es válida.

> **Aviso.** Esto se midió en Docker Desktop, o sea dentro de una VM y con el cliente de
> carga compartiendo la misma máquina. Los números **relativos** son justos —condiciones
> idénticas para todos—, los **absolutos** no son comparables con una corrida en Linux
> bare-metal y no deben citarse como tales.

| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext (mediana) | rps /json (mediana) | rps /db (mediana) |
|---|---|---|---|---|---|---|
| cero | 112.2 | 223 | 401.3 | 25598.4 | 25123.8 | 25098.6 |
| jooby | 114.6 | 405 | 129 | 25419.8 | 25317.0 | 25025.5 |
| vertx | 119.6 | 497 | 101.7 | 24689.6 | 24856.3 | 23787.0 |
| helidon | 112.4 | 540 | 113.6 | 24850.4 | 25540.6 | 24020.7 |
| javalin | 115.2 | 606 | 179.4 | 25027.5 | 24438.3 | 23542.0 |
| quarkus | 123.2 | 830 | 153 | 25246.3 | 25332.1 | 24771.0 |
| micronaut | 120.7 | 902 | 132.7 | 23040.2 | 22675.5 | 20118.8 |
| jxmvc | 110.2 | 933 | 236.4 | 23698.1 | 23831.1 | 22609.8 |
| spring | 127.3 | 1662 | 306.5 | 21772.5 | 22191.9 | 21334.9 |
