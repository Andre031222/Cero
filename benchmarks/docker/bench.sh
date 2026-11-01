#!/usr/bin/env bash
set -euo pipefail

SOLO_TABLA=0
if [ "${1:-}" = "--tabla" ]; then SOLO_TABLA=1; shift; fi

CONNS="${1:-64}"
DUR="${2:-20}"
REPS="${3:-3}"
CPUS="${BENCH_CPUS:-2}"
MEM="${BENCH_MEM:-1g}"
PORT="${BENCH_PORT:-8080}"
JAVA_OPTS="${BENCH_JAVA_OPTS:--Xmx256m}"
CPUSET="${BENCH_CPUSET:-}"
CLIENT_CPUS="${BENCH_CLIENT_CPUS:-}"
CLIENT_PREFIX=()
[ -n "$CLIENT_CPUS" ] && CLIENT_PREFIX=(taskset -c "$CLIENT_CPUS")

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/../results/RESULTS-docker.md"
CSV="$HERE/../results/raw-docker.csv"

APPS=(cero spring quarkus micronaut javalin helidon vertx jooby)
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
  [ -s "$CSV" ] || { echo "No hay $CSV que agregar."; exit 1; }
  CONNS=$(awk -F, 'NR==2{print $6}' "$CSV")
  DUR=$(awk -F, 'NR==2{print $7}' "$CSV")
  REPS=$(awk -F, 'NR==2{f=$1; e=$5} NR>1 && $1==f && $5==e{n++} END{print n+0}' "$CSV")
  PORT=$(awk -F, 'NR==2{split($5,a,":"); split(a[3],b,"/"); print b[1]}' "$CSV")
  echo "Rehaciendo la tabla desde $CSV (conns=$CONNS dur=${DUR}s reps=$REPS)."
fi

ahora_ms() {
  local t
  t=$(date +%s%3N 2>/dev/null)
  case "$t" in
    *N|"") python3 -c 'import time;print(int(time.time()*1000))' ;;
    *) echo "$t" ;;
  esac
}

BASE=""

wait_up() {
  local start now code
  start=$(ahora_ms)
  for _ in $(seq 1 600); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/plaintext" 2>/dev/null || echo 000)
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
  if [ "$fw" = "cero" ]; then
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
  [ -n "$JAVA_OPTS" ] && runargs+=(-e "JAVA_TOOL_OPTIONS=$JAVA_OPTS")
  [ -n "$CPUSET" ] && runargs+=(--cpuset-cpus="$CPUSET")
  if ! docker run -d --name "bench_$fw" "${runargs[@]}" -p "$PORT:8080" "$img" >/dev/null 2>"/tmp/bench-run-$fw.log"; then
    echo "  $fw: NO se pudo lanzar el contenedor —"; sed 's/^/    /' "/tmp/bench-run-$fw.log"
    continue
  fi

  ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "bench_$fw" 2>/dev/null)
  BASE="http://${ip:-localhost}:8080"
  [ -n "$ip" ] || BASE="http://localhost:$PORT"
  startup_ms=$(wait_up) || startup_ms=-1
  if [ "$startup_ms" = "-1" ]; then
    echo "  $fw NO arrancó — logs:"; docker logs --tail 20 "bench_$fw" || true
    docker rm -f "bench_$fw" >/dev/null 2>&1 || true
    continue
  fi
  echo "  arranque=${startup_ms}ms  imagen=${image_mb}MB"

  for ep in plaintext json ${BENCH_DB:+db}; do
    for r in $(seq 1 "$REPS"); do
      line=$( (cd "$HERE/../load" && ${CLIENT_PREFIX[@]+"${CLIENT_PREFIX[@]}"} java LoadClient "$BASE/$ep" "$CONNS" "$DUR" 5) 2>/dev/null )
      rss_mb=$(docker stats --no-stream --format '{{.MemUsage}}' "bench_$fw" | awk -F'/' '{gsub(/[^0-9.]/,"",$1); print $1}')
      echo "$fw,$image_mb,$startup_ms,$rss_mb,$line" >> "$CSV"
      e=$(echo "$line" | awk -F, '{print ($5+$6)+0}')
      [ "${e:-0}" -gt 0 ] && echo "  ⚠ $fw /$ep rep $r: errores/no-2xx=$e — medición SOSPECHOSA (revisa saturación/CPU del cliente)"
    done
  done

  docker rm -f "bench_$fw" >/dev/null 2>&1 || true
done

echo "CSV crudo -> $CSV"

med() {
  awk -F, -v f="$1" -v c="$2" -v ep="${3:-}" \
    '$1==f && (ep=="" || substr($5, length($5)-length(ep)+1)==ep){print $c}' "$CSV" \
    | sort -n | awk '{a[NR]=$1} END{print (NR? a[int((NR+1)/2)] : "-")}'
}

{
  echo "# Resultados dockerizados"
  echo
  echo "Generado por \`bench.sh\` — no editar a mano; se rehace con \`./bench.sh --tabla\`."
  echo
  echo "| Parámetro | Valor |"
  echo "|---|---|"
  echo "| Límites del contenedor | \`--cpus=$CPUS --memory=$MEM\`${CPUSET:+ \`--cpuset-cpus=$CPUSET\`} |"
  if [ -n "$JAVA_OPTS" ]; then
    echo "| Montón de la JVM | \`$JAVA_OPTS\`, idéntico para todos |"
  else
    echo "| Montón de la JVM | sin acotar — la columna de RSS **no** es comparable |"
  fi
  echo "| Carga | conns=$CONNS, dur=${DUR}s, reps=$REPS, warmup=5s |"
  echo "| Cliente | \`LoadClient\` desde el host${CLIENT_CPUS:+, fijado a los núcleos $CLIENT_CPUS}${CLIENT_CPUS:+ (aislado)} |"
  echo "| Host | $(uname -s) $(uname -r) $(uname -m) |"
  echo "| Base JRE | idéntica para todos |"
  echo "| \`/db\` | \`SELECT\` sobre H2 in-memory (1000 filas) + JSON; \`Db.java\` idéntico en todos salvo la línea \`package\` |"
  echo
  echo "Arranque, RSS y rps son la **mediana** de las $REPS repeticiones. El RSS se mide con el mismo
tope de montón para todos: sin él compara decisiones del recolector, no frameworks. \`⚠\` = el framework tuvo"
  echo "errores o respuestas no-2xx: esa fila NO es válida."
  if [ "$(uname -s)" != "Linux" ]; then
    echo
    echo "> **Aviso.** Esto se midió en Docker Desktop, o sea dentro de una VM y con el cliente de"
    echo "> carga compartiendo la misma máquina. Los números **relativos** son justos —condiciones"
    echo "> idénticas para todos—, los **absolutos** no son comparables con una corrida en Linux"
    echo "> bare-metal y no deben citarse como tales."
  fi
  echo
  echo "| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext (mediana) | rps /json (mediana) | rps /db (mediana) |"
  echo "|---|---|---|---|---|---|---|"
  for fw in "${APPS[@]}"; do
    im=$(awk -F, -v f="$fw" '$1==f{print $2; exit}' "$CSV")
    su=$(med "$fw" 3)
    rs=$(med "$fw" 4)
    pt=$(med "$fw" 11 "/plaintext")
    js=$(med "$fw" 11 "/json")
    db=$(med "$fw" 11 "/db")
    err=$(awk -F, -v f="$fw" '$1==f{e+=$9+$10} END{print e+0}' "$CSV")
    flag=""; [ "${err:-0}" -gt 0 ] && flag=" ⚠"
    if [ -n "$im" ]; then
      printf '%s\t| %s | %s | %s | %s | %s | %s | %s |\n' "$su" "${fw}${flag}" "$im" "$su" "$rs" "$pt" "$js" "$db"
    else
      printf '%s\t| %s | (no se midió: build o arranque falló) | | | | | |\n' 999999999 "$fw"
    fi
  done | sort -n -k1,1 | cut -f2-
} > "$OUT"

echo "Tabla -> $OUT"
cat "$OUT"

total_err=$(awk -F, 'NR>1{e+=$9+$10} END{print e+0}' "$CSV")
if [ "${total_err:-0}" -gt 0 ]; then
  echo ""
  echo "⚠⚠ ATENCIÓN: $total_err errores/no-2xx en total. Las filas marcadas con ⚠ NO son válidas para el paper."
  echo "   Causas típicas: cliente saturado (usa BENCH_CLIENT_CPUS), poca CPU/RAM al contenedor, o el server falla bajo carga."
  exit 3
fi
