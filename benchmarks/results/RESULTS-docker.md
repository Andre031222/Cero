# Resultados dockerizados

Generado por `bench.sh` — no editar a mano; se rehace con `./bench.sh --tabla`.

| Parámetro | Valor |
|---|---|
| Límites del contenedor | `--cpus=4 --memory=2g` `--cpuset-cpus=0-3` |
| Carga | conns=64, dur=30s, reps=5, warmup=5s |
| Cliente | `LoadClient` desde el host |
| Host | Darwin 25.5.0 arm64 |
| Base JRE | idéntica para los seis |
| `/db` | `SELECT` sobre H2 in-memory (1000 filas) + JSON; `Db.java` byte-idéntico en todos |

Arranque, RSS y rps son la **mediana** de las 5 repeticiones. `⚠` = el framework tuvo
errores o respuestas no-2xx: esa fila NO es válida.

> **Aviso.** Esto se midió en Docker Desktop, o sea dentro de una VM y con el cliente de
> carga compartiendo la misma máquina. Los números **relativos** son justos —condiciones
> idénticas para los seis—, los **absolutos** no son comparables con una corrida en Linux
> bare-metal y no deben citarse como tales.

| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext (mediana) | rps /json (mediana) | rps /db (mediana) |
|---|---|---|---|---|---|---|
| luxcore | 110.3 | 106 | 136.4 | 26425.5 | 25431.0 | 25931.0 |
| jxmvc | 110.1 | 698 | 191.5 | 24239.5 | 23307.4 | 18771.2 |
| spring | 127.3 | 1467 | 352.5 | 19808.9 | 20431.6 | 20087.8 |
| quarkus | 123.2 | 707 | 259.8 | 25515.2 | 22744.2 | 21258.1 |
| micronaut | 120.7 | 838 | 201.2 | 18380.6 | 19087.6 | 17213.4 |
| javalin | 115.2 | 451 | 285.7 | 21993.9 | 25125.2 | 24458.6 |
