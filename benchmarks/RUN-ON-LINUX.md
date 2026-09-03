# Correr el benchmark en Linux bare-metal (Arch, Debian, etc.)

La forma recomendada de obtener las cifras **de publicación**: Linux nativo, sin la VM de Docker
Desktop y sin apps de producción alrededor. Con Docker instalado, es **un comando**.

## Requisitos (Arch)
```bash
sudo pacman -S docker jdk-openjdk git      # Docker + un JDK (para el LoadClient) + git
sudo systemctl start docker
sudo usermod -aG docker $USER              # re-login para usar docker sin sudo
```

## Correr
```bash
git clone https://github.com/Andre031222/Cero.git
cd Cero/benchmarks/docker
./bench.sh 64 30 5                          # 64 conexiones, 30 s, 5 repeticiones

# Incluir además Quarkus compilado a binario NATIVO (GraalVM, build lento ~5-10 min):
BENCH_NATIVE=1 ./bench.sh 64 30 5

# Ajustar recursos (fija CPU/RAM para reproducibilidad):
BENCH_CPUS=4 BENCH_MEM=2g BENCH_NATIVE=1 ./bench.sh 64 30 5
```

Salida:
- `../results/RESULTS-docker.md` — tabla (mediana de rps por framework/endpoint).
- `../results/raw-docker.csv` — cada repetición (para estadística).

## Núcleos híbridos (Intel 12ª generación y posteriores) — **léelo antes de medir**

Un i5 de 12ª generación no tiene seis núcleos iguales: tiene **P-cores** (rápidos) y **E-cores**
(eficientes, bastante más lentos). El planificador mueve procesos entre unos y otros según le
parece, y eso **arruina la reproducibilidad**: la misma corrida da cifras distintas según dónde
cayó cada contenedor, y peor, un framework puede quedar en P-cores y otro en E-cores dentro de la
misma tanda. No es ruido que se corrija con más repeticiones — es sesgo.

Averigua cuáles son tus P-cores:

```bash
cat /sys/devices/cpu_core/cpus     # los P-cores, p. ej. 0-7
cat /sys/devices/cpu_atom/cpus     # los E-cores, p. ej. 8-15
lscpu -e                           # y para verlo con sus frecuencias
```

Y fija **el servidor a P-cores** dejando el resto para el generador de carga:

```bash
# ejemplo con P-cores 0-7: el contenedor en 0-3, el cliente en 4-7
BENCH_CPUSET="0-3" BENCH_CPUS=4 BENCH_MEM=2g ./bench.sh 64 30 5
```

**Que el cliente y el servidor no compartan núcleos** es la regla que más cambia los resultados.
Si el generador de carga compite con el servidor por la misma CPU, no estás midiendo el
framework: estás midiendo quién ganó la pelea por el núcleo. Fue exactamente el defecto de la
primera medición casera de este proyecto —cliente y servidor juntos, con `ab`— y por eso sus
94 610 rps no valían nada.

Con 32 GB de RAM vas sobrado: el techo aquí es la CPU, no la memoria.

## Buenas prácticas para números de paper
- Cerrar navegador/IDE; `sudo cpupower frequency-set -g performance` si está disponible.
- N≥5 repeticiones; reportar **mediana + [min, max]**.
- Barrer concurrencia: `for c in 1 8 32 64 128 256; do ./bench.sh $c 30 5; done`.
- Anotar el entorno exacto (CPU, RAM, kernel, versión de JDK y de Docker) en el paper.
- La 1ª repetición (JIT frío) la descarta la mediana; aun así, warmup de 5 s ya incluido.

## Qué anotar

Para que la corrida sea citable, el paper necesita el entorno exacto. Cópialo de aquí:

```bash
lscpu | head -20                       # modelo, núcleos, frecuencias
uname -r                               # kernel
docker --version && java -version      # versiones
cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor   # gobernador
```

Y en la tabla del paper, la mediana con su rango `[min, max]` — nunca la media sola.

## Nota sobre el VPS
No se recomienda correr la carga en el VPS de producción (comparte CPU con apps en vivo, y su
JDK 25 no compila algunos frameworks que targetean 17/21). Arch bare-metal es la mejor opción.
