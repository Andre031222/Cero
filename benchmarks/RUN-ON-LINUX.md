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
git clone https://github.com/Andre031222/jxmvc.git
cd jxmvc/benchmarks/docker
./bench.sh 64 30 5                          # 64 conexiones, 30 s, 5 repeticiones

# Incluir además Quarkus compilado a binario NATIVO (GraalVM, build lento ~5-10 min):
BENCH_NATIVE=1 ./bench.sh 64 30 5

# Ajustar recursos (fija CPU/RAM para reproducibilidad):
BENCH_CPUS=4 BENCH_MEM=2g BENCH_NATIVE=1 ./bench.sh 64 30 5
```

Salida:
- `../results/RESULTS-docker.md` — tabla (mediana de rps por framework/endpoint).
- `../results/raw-docker.csv` — cada repetición (para estadística).

## Buenas prácticas para números de paper
- Cerrar navegador/IDE; `sudo cpupower frequency-set -g performance` si está disponible.
- N≥5 repeticiones; reportar **mediana + [min, max]**.
- Barrer concurrencia: `for c in 1 8 32 64 128 256; do ./bench.sh $c 30 5; done`.
- Anotar el entorno exacto (CPU, RAM, kernel, versión de JDK y de Docker) en el paper.
- La 1ª repetición (JIT frío) la descarta la mediana; aun así, warmup de 5 s ya incluido.

## Alternativa sin Docker (procesos JVM directos)
Si prefieres no usar Docker, `bench-native-bins.sh` corre binarios ya construidos. Requiere
extraer los JAR de las imágenes (o construirlos con un JDK 17) — ver el script. En una máquina
con JDK 17/21 limpio, `bench-native.sh` compila y corre todo sin Docker.

## Nota sobre el VPS
No se recomienda correr la carga en el VPS de producción (comparte CPU con apps en vivo, y su
JDK 25 no compila algunos frameworks que targetean 17/21). Arch bare-metal es la mejor opción.
