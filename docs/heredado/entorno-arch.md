# Benchmark de JxMVC en Arch Linux — instrucciones paso a paso

Objetivo: correr el benchmark en **Linux bare-metal** (tu Arch dual-boot) para obtener las cifras
**de calidad de publicación** de JxMVC vs Spring Boot, Quarkus, Micronaut y Javalin. Es
prácticamente **un comando**; Docker se encarga de construir cada framework en igualdad de
condiciones y de escribir la tabla de resultados.

> Tiempo estimado: ~15–25 min la 1ª vez (descarga imágenes y dependencias Maven). Las siguientes
> corridas son más rápidas (todo queda cacheado).

---

## 1. Instalar lo necesario (una sola vez)

```bash
sudo pacman -Syu --needed docker jdk-openjdk git cpupower
```
- `docker` → construye y corre cada framework aislado.
- `jdk-openjdk` → un JDK en el host (lo usa el generador de carga `LoadClient`).
- `git` → para clonar el repo.
- `cpupower` → fija la frecuencia de CPU en modo `performance` (clave para cifras estables, ver §8).

## 2. Arrancar Docker y darte permiso (una sola vez)

```bash
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
```
**Importante:** cierra sesión y vuelve a entrar (o reinicia) para que el grupo `docker` tome
efecto. Verifica que funciona sin sudo:
```bash
docker run --rm hello-world
```
Si imprime "Hello from Docker!", listo.

## 3. Clonar el repositorio

```bash
git clone https://github.com/Andre031222/jxmvc.git
cd jxmvc/benchmarks/docker
```

## 4. Correr el benchmark

**Opción A — completo con la fila GraalVM nativo (recomendado para el paper):**
```bash
BENCH_NATIVE=1 ./bench.sh 64 30 5
```

**Opción B — sin el nativo (más rápido, las 5 en JVM):**
```bash
./bench.sh 64 30 5
```

Los números son: `64` conexiones concurrentes, `30` segundos de medición por prueba, `5`
repeticiones (se reporta la **mediana** de arranque, RSS y rps). Sin argumentos los defaults son
`64 20 3`. Puedes ajustar CPU/RAM fijas para reproducibilidad:
```bash
BENCH_CPUS=4 BENCH_MEM=2g BENCH_NATIVE=1 ./bench.sh 64 30 5
```

**Recomendado para el paper — aislar el generador de carga.** El `LoadClient` corre en el host y
compite por CPU con el contenedor. Para que midas el *servidor* y no el cliente, fija el contenedor
y el cliente a **núcleos distintos** (ejemplo para 8 núcleos: 0-3 al contenedor, 4-7 al cliente):
```bash
BENCH_CPUS=4 BENCH_CPUSET=0-3 BENCH_CLIENT_CPUS=4-7 BENCH_MEM=2g BENCH_NATIVE=1 ./bench.sh 64 30 5
```
- `BENCH_CPUSET` → clava el contenedor a esos núcleos (`docker --cpuset-cpus`).
- `BENCH_CLIENT_CPUS` → clava el `LoadClient` a otros (`taskset`). Necesita el paquete `taskset` (util-linux, ya viene en Arch).

> El script es **resiliente**: si algún framework no construye o no arranca, lo registra con sus
> logs y sigue con los demás. No se detiene todo por uno. **Pero** si algún framework devuelve
> errores/no-2xx bajo carga, marca esa fila con `⚠` y termina con código ≠ 0: esas cifras **no**
> son válidas (típicamente el cliente se saturó — usa `BENCH_CLIENT_CPUS`).

## 5. Resultado

Cuando termine, se generan **dos archivos**:

- `../results/RESULTS-docker.md` → la **tabla** (imagen, arranque, RSS, throughput por framework).
- `../results/raw-docker.csv` → cada repetición en crudo (para estadística: mediana, min, max).

Míralos así:
```bash
cat ../results/RESULTS-docker.md
```

**Antes de confiar en las cifras — valida que no hubo errores.** Una corrida con errores o
respuestas no-2xx no sirve para el paper (mide fallos, no rendimiento). El script ya te avisa, pero
compruébalo tú mismo:
```bash
# Debe imprimir 0. Columnas 9 y 10 del CSV son errores y no-2xx.
awk -F, 'NR>1{e+=$9+$10} END{print "errores+no2xx =", e+0}' ../results/raw-docker.csv
```
Si es `> 0`, o ves filas con `⚠` en la tabla, **repite** aislando el cliente con `BENCH_CLIENT_CPUS`
(ver §4) y/o dando más CPU/RAM al contenedor.

## 6. Qué me tienes que pasar

Pégame (o súbeme) el contenido de **`RESULTS-docker.md`** y **`raw-docker.csv`**. Con eso
consolido los números oficiales del paper. Y dime, para el "Entorno" del paper:
```bash
# ejecuta esto y pásame la salida:
echo "CPU:  $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)"
echo "Nucleos: $(nproc)"
echo "RAM:  $(free -h | awk '/Mem:/{print $2}')"
echo "Kernel: $(uname -r)"
echo "JDK:  $(java -version 2>&1 | head -1)"
echo "Docker: $(docker --version)"
```

---

## 7. (Opcional) Barrido de concurrencia — para la curva del paper

Si quieres la curva completa throughput/latencia a distintas cargas:
```bash
for c in 1 8 32 64 128 256; do ./bench.sh "$c" 20 3; mv ../results/raw-docker.csv ../results/raw-$c.csv; done
```
Pásame todos los `raw-*.csv`.

## 8. Buenas prácticas para cifras de publicación

- Cierra navegador/IDE y apps pesadas antes de correr.
- **Governor a `performance`:** `sudo cpupower frequency-set -g performance` (evita que el escalado
  dinámico de frecuencia meta ruido en los rps).
- **Desactiva turbo boost** para que la frecuencia no dependa de la temperatura (Intel):
  `echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo`. En corridas largas en **laptop**
  vigila el *throttling térmico*: si la máquina se calienta, los últimos frameworks medidos salen
  penalizados. Deja enfriar entre tandas.
- **Aísla el cliente** de la carga (`BENCH_CLIENT_CPUS`, ver §4): es lo que más afecta la validez.
- **Fija CPU/RAM explícitas** (`BENCH_CPUS`/`BENCH_MEM`); no dependas de los defaults (2 CPU / 1g).
- Sobre las repeticiones: el `LoadClient` ya hace *warmup* (calienta el JIT antes de medir), así que
  cada repetición mide en estado estacionario. La **mediana** de las repeticiones absorbe el ruido
  restante; para el paper reporta también **min/max o el IC** desde el CSV, no solo la mediana.

## 9. Problemas comunes

| Síntoma | Solución |
|---|---|
| `permission denied` al usar docker | No cerraste sesión tras el `usermod -aG docker`. Re-login o `newgrp docker`. |
| `port is already allocated` (8080) | Algo usa el 8080. Ciérralo: `sudo ss -ltnp | grep :8080` y para ese proceso. |
| La fila `quarkus-native` tarda mucho | Normal: compilar a binario nativo con GraalVM toma 5–10 min. Ten paciencia o usa la Opción B. |
| Un framework dice "no arrancó" | El script sigue con los demás; pásame igual el `RESULTS-docker.md` y reviso ese caso. |
| Poco espacio en disco | Las imágenes ocupan ~2–3 GB en total. Libera con `docker system prune -af` al terminar. |

---

Cuando tengas el `RESULTS-docker.md`, me lo pasas y **armamos el paper con las cifras definitivas**.
