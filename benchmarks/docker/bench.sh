#!/usr/bin/env bash
# Benchmark dockerizado, reproducible y de un solo comando.
# Construye cada framework como imagen (misma base JRE, mismos límites de CPU/RAM),
# mide arranque en frío, RSS, tamaño de imagen y throughput/latencia, y escribe
# ../results/RESULTS-docker.md automáticamente.
#
# Requisitos: Docker en marcha + un JDK en el host (para el LoadClient).
# Uso: ./bench.sh [conexiones] [segundos] [repeticiones]
#      ./bench.sh --tabla              rehace la tabla desde el CSV, sin volver a medir
set -euo pipefail

SOLO_TABLA=0
if [ "${1:-}" = "--tabla" ]; then SOLO_TABLA=1; shift; fi

CONNS="${1:-64}"
DUR="${2:-20}"
REPS="${3:-3}"
CPUS="${BENCH_CPUS:-2}"
MEM="${BENCH_MEM:-1g}"
PORT="${BENCH_PORT:-8080}"
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

# Orden: jxmvc primero, luego los rivales. El contexto de build de cero es el REPO; el de los
# demás es su propia carpeta.
#
# JxMVC ya no vive aquí: este repositorio es solo Cero. Para incluirlo en la comparación hay que
# decir dónde está su código, y si no se dice, se salta diciéndolo — antes se intentaba compilar
# y fallaba, que es la forma de perder un contendiente sin enterarse.
#
#   JXMVC_SRC=~/ruta/a/jxmvc-core ./bench.sh
APPS=(cero spring quarkus micronaut javalin)
if [ -n "${JXMVC_SRC:-}" ] && [ -f "${JXMVC_SRC}/pom.xml" ]; then
  APPS=(cero jxmvc "${APPS[@]:1}")
elif [ -n "${JXMVC_SRC:-}" ]; then
  echo "JXMVC_SRC=$JXMVC_SRC no tiene pom.xml; se omite JxMVC de la comparación."
fi
# BENCH_NATIVE=1 añade Quarkus compilado a binario nativo (GraalVM) — build lento (~5-10 min).
[ "${BENCH_NATIVE:-0}" = "1" ] && APPS+=(quarkus-native)

if [ "$SOLO_TABLA" = 0 ]; then
  command -v java >/dev/null || { echo "Falta java en el host (para LoadClient)"; exit 1; }
  docker info >/dev/null 2>&1 || { echo "Docker no está corriendo. Inicia Docker Desktop."; exit 1; }
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "El puerto $PORT ya está ocupado. Libéralo o usa BENCH_PORT=<otro> ./bench.sh"; exit 1
  fi
  [ -f "$HERE/../load/LoadClient.class" ] || (cd "$HERE/../load" && javac LoadClient.java)

  echo "framework,image_mb,startup_ms,rss_mb,endpoint,conns,dur,requests,errors,non2xx,rps,meanMs,p50,p90,p95,p99" > "$CSV"
else
  # Los parámetros de la corrida salen del propio CSV, no de los argumentos: la tabla
  # tiene que describir lo que se midió, no lo que se teclee al rehacerla.
  [ -s "$CSV" ] || { echo "No hay $CSV que agregar."; exit 1; }
  CONNS=$(awk -F, 'NR==2{print $6}' "$CSV")
  DUR=$(awk -F, 'NR==2{print $7}' "$CSV")
  REPS=$(awk -F, 'NR==2{f=$1; e=$5} NR>1 && $1==f && $5==e{n++} END{print n+0}' "$CSV")
  PORT=$(awk -F, 'NR==2{split($5,a,":"); split(a[3],b,"/"); print b[1]}' "$CSV")
  echo "Rehaciendo la tabla desde $CSV (conns=$CONNS dur=${DUR}s reps=$REPS)."
fi

# Milisegundos desde época. GNU date admite %3N; BSD (macOS) no, así que se cae a python3.
ahora_ms() {
  local t
  t=$(date +%s%3N 2>/dev/null)
  case "$t" in
    *N|"") python3 -c 'import time;print(int(time.time()*1000))' ;;
    *) echo "$t" ;;
  esac
}

wait_up() {  # espera 200 en /plaintext, imprime ms de arranque
  local start now code
  start=$(ahora_ms)
  for _ in $(seq 1 600); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/plaintext" 2>/dev/null || echo 000)
    [ "$code" = "200" ] && { now=$(ahora_ms); echo $((now-start)); return 0; }
    sleep 0.1
  done
  echo "-1"; return 1
}

for fw in "${APPS[@]}"; do
  [ "$SOLO_TABLA" = 1 ] && break
  echo "──────────── $fw ────────────"
  img="bench-$fw"
  blog="/tmp/bench-build-$fw.log"
  bok=1
  if [ "$fw" = "jxmvc" ]; then
    # Un contexto de build sólo puede tener una raíz, así que se arma uno temporal con el
    # código de JxMVC —que vive fuera— y la aplicación del banco, que vive aquí.
    ctx="$(mktemp -d)"
    cp -R "$JXMVC_SRC" "$ctx/jxmvc-core"
    mkdir -p "$ctx/app" && cp -R "$HERE/apps/jxmvc/app/." "$ctx/app/"
    docker build -t "$img" -f "$HERE/apps/jxmvc/Dockerfile" "$ctx" >"$blog" 2>&1 || bok=0
    rm -rf "$ctx"
  elif [ "$fw" = "cero" ]; then
    docker build -t "$img" -f "$HERE/apps/$fw/Dockerfile" "$REPO" >"$blog" 2>&1 || bok=0
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
  # `set -e` mataba el banco entero si un solo `docker run` fallaba (p. ej. puerto ocupado).
  if ! docker run -d --name "bench_$fw" "${runargs[@]}" -p "$PORT:8080" "$img" >/dev/null 2>"/tmp/bench-run-$fw.log"; then
    echo "  $fw: NO se pudo lanzar el contenedor —"; sed 's/^/    /' "/tmp/bench-run-$fw.log"
    continue
  fi

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
  echo "Generado por \`bench.sh\` — no editar a mano; se rehace con \`./bench.sh --tabla\`."
  echo
  echo "| Parámetro | Valor |"
  echo "|---|---|"
  echo "| Límites del contenedor | \`--cpus=$CPUS --memory=$MEM\`${CPUSET:+ \`--cpuset-cpus=$CPUSET\`} |"
  echo "| Carga | conns=$CONNS, dur=${DUR}s, reps=$REPS, warmup=5s |"
  echo "| Cliente | \`LoadClient\` desde el host${CLIENT_CPUS:+, fijado a los núcleos $CLIENT_CPUS}${CLIENT_CPUS:+ (aislado)} |"
  echo "| Host | $(uname -s) $(uname -r) $(uname -m) |"
  echo "| Base JRE | idéntica para los seis |"
  echo "| \`/db\` | \`SELECT\` sobre H2 in-memory (1000 filas) + JSON; \`Db.java\` byte-idéntico en todos |"
  echo
  echo "Arranque, RSS y rps son la **mediana** de las $REPS repeticiones. \`⚠\` = el framework tuvo"
  echo "errores o respuestas no-2xx: esa fila NO es válida."
  if [ "$(uname -s)" != "Linux" ]; then
    echo
    echo "> **Aviso.** Esto se midió en Docker Desktop, o sea dentro de una VM y con el cliente de"
    echo "> carga compartiendo la misma máquina. Los números **relativos** son justos —condiciones"
    echo "> idénticas para los seis—, los **absolutos** no son comparables con una corrida en Linux"
    echo "> bare-metal y no deben citarse como tales."
  fi
  echo
  echo "| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext (mediana) | rps /json (mediana) | rps /db (mediana) |"
  echo "|---|---|---|---|---|---|---|"
  for fw in "${APPS[@]}"; do
    im=$(awk -F, -v f="$fw" '$1==f{print $2; exit}' "$CSV")   # imagen: valor constante por framework
    su=$(med "$fw" 3)                                          # arranque: mediana
    rs=$(med "$fw" 4)                                          # RSS: mediana
    # El filtro usa $PORT, no 8080 fijo: con BENCH_PORT distinto la tabla salía vacía
    # aunque el CSV tuviera los datos.
    pt=$(med "$fw" 11 "http://localhost:$PORT/plaintext")
    js=$(med "$fw" 11 "http://localhost:$PORT/json")
    db=$(med "$fw" 11 "http://localhost:$PORT/db")            # "-" si no se corrió con BENCH_DB=1
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
