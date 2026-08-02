#!/usr/bin/env bash
# Benchmark dockerizado, reproducible y de un solo comando.
# Construye cada framework como imagen (misma base JRE, mismos límites de CPU/RAM),
# mide arranque en frío, RSS, tamaño de imagen y throughput/latencia, y escribe
# ../results/RESULTS-docker.md automáticamente.
#
# Requisitos: Docker en marcha + un JDK en el host (para el LoadClient).
# Uso: ./bench.sh [conexiones] [segundos] [repeticiones]
set -euo pipefail

CONNS="${1:-64}"
DUR="${2:-20}"
REPS="${3:-3}"
CPUS="${BENCH_CPUS:-2}"
MEM="${BENCH_MEM:-1g}"
PORT=8080
# Reproducibilidad avanzada (opcional):
#   BENCH_CPUSET="0,1"      -> fija el contenedor a esos núcleos (docker --cpuset-cpus)
#   BENCH_CLIENT_CPUS="2,3" -> fija el LoadClient a OTROS núcleos (taskset) para no competir
CPUSET="${BENCH_CPUSET:-}"
CLIENT_CPUS="${BENCH_CLIENT_CPUS:-}"
CLIENT_PREFIX=()
[ -n "$CLIENT_CPUS" ] && CLIENT_PREFIX=(taskset -c "$CLIENT_CPUS")

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/../results/RESULTS-docker.md"
CSV="$HERE/../results/raw-docker.csv"

# Orden: jxmvc primero, luego los rivales. El contexto de build de jxmvc es el REPO
# (necesita compilar JxMVC.Core); el de los demás es su propia carpeta.
APPS=(jxmvc spring quarkus micronaut javalin)
# BENCH_NATIVE=1 añade Quarkus compilado a binario nativo (GraalVM) — build lento (~5-10 min).
[ "${BENCH_NATIVE:-0}" = "1" ] && APPS+=(quarkus-native)

command -v java >/dev/null || { echo "Falta java en el host (para LoadClient)"; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker no está corriendo. Inicia Docker Desktop."; exit 1; }
[ -f "$HERE/../load/LoadClient.class" ] || (cd "$HERE/../load" && javac LoadClient.java)

echo "framework,image_mb,startup_ms,rss_mb,endpoint,conns,dur,requests,errors,non2xx,rps,meanMs,p50,p90,p95,p99" > "$CSV"

wait_up() {  # espera 200 en /plaintext, imprime ms de arranque
  local start now code
  start=$(date +%s%3N)
  for _ in $(seq 1 600); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/plaintext" 2>/dev/null || echo 000)
    [ "$code" = "200" ] && { now=$(date +%s%3N); echo $((now-start)); return 0; }
    sleep 0.1
  done
  echo "-1"; return 1
}

for fw in "${APPS[@]}"; do
  echo "──────────── $fw ────────────"
  img="bench-$fw"
  blog="/tmp/bench-build-$fw.log"
  bok=1
  if [ "$fw" = "jxmvc" ]; then
    docker build -t "$img" -f "$HERE/apps/jxmvc/Dockerfile" "$REPO" >"$blog" 2>&1 || bok=0
  elif [ "$fw" = "quarkus-native" ]; then
    docker build -t "$img" -f "$HERE/apps/quarkus/Dockerfile.native" "$HERE/apps/quarkus" >"$blog" 2>&1 || bok=0
  else
    docker build -t "$img" "$HERE/apps/$fw" >"$blog" 2>&1 || bok=0
  fi
  if [ "$bok" = 0 ]; then
    echo "  $fw: BUILD FALLÓ — últimas líneas:"; tail -25 "$blog"; echo "  (log completo: $blog)"; continue
  fi
  image_mb=$(docker image inspect "$img" --format '{{.Size}}' | awk '{printf "%.1f", $1/1048576}')

  docker rm -f "bench_$fw" >/dev/null 2>&1 || true
  runargs=(--cpus="$CPUS" --memory="$MEM")
  [ -n "$CPUSET" ] && runargs+=(--cpuset-cpus="$CPUSET")
  docker run -d --name "bench_$fw" "${runargs[@]}" -p "$PORT:8080" "$img" >/dev/null

  startup_ms=$(wait_up) || startup_ms=-1
  if [ "$startup_ms" = "-1" ]; then
    echo "  $fw NO arrancó — logs:"; docker logs --tail 20 "bench_$fw" || true
    docker rm -f "bench_$fw" >/dev/null 2>&1 || true
    continue
  fi
  echo "  arranque=${startup_ms}ms  imagen=${image_mb}MB"

  # warmup + medición de carga (host -> contenedor).
  # BENCH_DB=1 añade el endpoint /db (SELECT real sobre H2 in-memory).
  for ep in plaintext json ${BENCH_DB:+db}; do
    for r in $(seq 1 "$REPS"); do
      line=$( (cd "$HERE/../load" && ${CLIENT_PREFIX[@]+"${CLIENT_PREFIX[@]}"} java LoadClient "http://localhost:$PORT/$ep" "$CONNS" "$DUR" 5) 2>/dev/null )
      rss_mb=$(docker stats --no-stream --format '{{.MemUsage}}' "bench_$fw" | awk -F'/' '{gsub(/[^0-9.]/,"",$1); print $1}')
      echo "$fw,$image_mb,$startup_ms,$rss_mb,$line" >> "$CSV"
      # Validez: errores/no-2xx invalidan la medición (posible saturación del cliente o del server).
      e=$(echo "$line" | awk -F, '{print ($5+$6)+0}')
      [ "${e:-0}" -gt 0 ] && echo "  ⚠ $fw /$ep rep $r: errores/no-2xx=$e — medición SOSPECHOSA (revisa saturación/CPU del cliente)"
    done
  done

  docker rm -f "bench_$fw" >/dev/null 2>&1 || true
done

echo "CSV crudo -> $CSV"

# Mediana de la columna $2 (1-indexed del CSV) para el framework $1; filtro opcional de endpoint en $3.
med() {
  awk -F, -v f="$1" -v c="$2" -v ep="${3:-}" \
    '$1==f && (ep=="" || $5==ep){print $c}' "$CSV" \
    | sort -n | awk '{a[NR]=$1} END{print (NR? a[int((NR+1)/2)] : "-")}'
}

# Agrega el CSV a una tabla Markdown (mediana por framework/endpoint).
{
  echo "# Resultados dockerizados"
  echo
  echo "Entorno: \`docker --cpus=$CPUS --memory=$MEM\`, misma base JRE. conns=$CONNS, dur=${DUR}s, reps=$REPS."
  echo "Generado por \`bench.sh\`. Números relativos comparables; ver README §8 (validez)."
  echo "Arranque/RSS/rps son **mediana** de las $REPS repeticiones. \`⚠\` = el framework tuvo errores/no-2xx: cifra NO válida."
  echo
  echo "| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext (mediana) | rps /json (mediana) | rps /db (mediana) |"
  echo "|---|---|---|---|---|---|---|"
  for fw in "${APPS[@]}"; do
    im=$(awk -F, -v f="$fw" '$1==f{print $2; exit}' "$CSV")   # imagen: valor constante por framework
    su=$(med "$fw" 3)                                          # arranque: mediana
    rs=$(med "$fw" 4)                                          # RSS: mediana
    pt=$(med "$fw" 11 "http://localhost:8080/plaintext")
    js=$(med "$fw" 11 "http://localhost:8080/json")
    db=$(med "$fw" 11 "http://localhost:8080/db")             # "-" si no se corrió con BENCH_DB=1
    err=$(awk -F, -v f="$fw" '$1==f{e+=$9+$10} END{print e+0}' "$CSV")
    flag=""; [ "${err:-0}" -gt 0 ] && flag=" ⚠"
    [ -n "$im" ] && echo "| ${fw}${flag} | $im | $su | $rs | $pt | $js | $db |" || echo "| $fw | (no arrancó) | | | | | |"
  done
} > "$OUT"

echo "Tabla -> $OUT"
cat "$OUT"

# Validez global: si hubo cualquier error/no-2xx, avisar fuerte y salir con código != 0.
total_err=$(awk -F, 'NR>1{e+=$9+$10} END{print e+0}' "$CSV")
if [ "${total_err:-0}" -gt 0 ]; then
  echo ""
  echo "⚠⚠ ATENCIÓN: $total_err errores/no-2xx en total. Las filas marcadas con ⚠ NO son válidas para el paper."
  echo "   Causas típicas: cliente saturado (usa BENCH_CLIENT_CPUS), poca CPU/RAM al contenedor, o el server falla bajo carga."
  exit 3
fi
