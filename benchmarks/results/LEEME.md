# Resultados

Vacío a propósito. Las tablas anteriores incluían un contendiente que ya no forma parte de la
comparación, y editar filas de una medición publicada es falsearla: se retiran y se vuelve a
medir.

Para regenerarlas, en una máquina limpia y aislada (ver [`../README.md`](../README.md) §3 y §6):

```bash
cd benchmarks/docker
BENCH_DB=1 ./bench.sh 64 30 5
```

`bench.sh` escribe `RESULTS-docker.md` y `raw-docker.csv` aquí. Una tabla sin su CSV al lado no
es un resultado.
