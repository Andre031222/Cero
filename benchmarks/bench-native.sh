#!/usr/bin/env bash
# Benchmark NATIVO (sin Docker) para Linux — arranca cada framework como proceso JVM en un
# puerto propio, mide arranque en frío, RSS y throughput/latencia, y escribe RESULTS-server.md.
# Pensado para correr en el VPS en un puerto que NO choque con producción.
#
# Requisitos: JDK + Maven en el host. El core jxmvc-core:3.4.0 debe estar en el repo local
# de Maven (instalar antes con: mvn install:install-file ...). Descarga un Tomcat propio para JxMVC.
#
# Uso: PORT=8099 ./bench-native.sh [conexiones] [segundos] [repeticiones]
set -uo pipefail
PORT="${PORT:-8099}"
CONNS="${1:-48}"
DUR="${2:-15}"
REPS="${3:-3}"
TOMCAT_VER="${TOMCAT_VER:-10.1.31}"

HERE="$(cd "$(dirname "$0")" && pwd)"
APPS="$HERE/docker/apps"
WORK="${WORK:-/tmp/jxbench}"
CSV="$HERE/results/raw-server.csv"
OUT="$HERE/results/RESULTS-server.md"
mkdir -p "$WORK" "$HERE/results"

command -v java >/dev/null || { echo "falta java"; exit 1; }
command -v mvn  >/dev/null || { echo "falta maven"; exit 1; }
[ -f "$HERE/load/LoadClient.class" ] || (cd "$HERE/load" && javac LoadClient.java)

echo "framework,startup_ms,rss_mb,endpoint,conns,dur,requests,errors,non2xx,rps,meanMs,p50,p90,p95,p99" > "$CSV"

wait_up() { local s n c; s=$(date +%s%3N)
  for _ in $(seq 1 600); do
    c=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/plaintext" 2>/dev/null || echo 000)
    [ "$c" = "200" ] && { n=$(date +%s%3N); echo $((n-s)); return 0; }
    sleep 0.1
  done; echo -1; return 1; }

pid_on_port() { ss -ltnp "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1; }
rss_of() { local p="$1"; [ -r "/proc/$p/status" ] && awk '/VmRSS/{printf "%.1f", $2/1024}' "/proc/$p/status" || echo 0; }
free_port() { local p; p=$(pid_on_port); [ -n "$p" ] && kill "$p" 2>/dev/null; sleep 2; p=$(pid_on_port); [ -n "$p" ] && kill -9 "$p" 2>/dev/null; sleep 1; }

# Descarga un Tomcat propio (solo para JxMVC), en puerto $PORT.
setup_tomcat() {
  local t="$WORK/tomcat"
  if [ ! -x "$t/bin/catalina.sh" ]; then
    echo "  descargando Tomcat $TOMCAT_VER ..."
    curl -sL "https://archive.apache.org/dist/tomcat/tomcat-10/v$TOMCAT_VER/bin/apache-tomcat-$TOMCAT_VER.tar.gz" -o "$WORK/tomcat.tgz" || return 1
    mkdir -p "$t"; tar xzf "$WORK/tomcat.tgz" -C "$t" --strip-components=1 || return 1
    sed -i "s/port=\"8080\"/port=\"$PORT\"/" "$t/conf/server.xml"
  fi
  rm -rf "$t"/webapps/*
}

run_app() {  # $1=nombre  $2=comando-de-arranque
  local fw="$1" cmd="$2" startup pid im
  echo "──────────── $fw ────────────"
  free_port
  bash -c "$cmd" >"$WORK/$fw.log" 2>&1 &
  startup=$(wait_up) || startup=-1
  if [ "$startup" = "-1" ]; then echo "  $fw NO arrancó:"; tail -12 "$WORK/$fw.log"; free_port; return; fi
  pid=$(pid_on_port)
  echo "  arranque=${startup}ms  pid=$pid"
  for ep in plaintext json; do
    for r in $(seq 1 "$REPS"); do
      local line rss
      line=$( (cd "$HERE/load" && java LoadClient "http://127.0.0.1:$PORT/$ep" "$CONNS" "$DUR" 5) 2>/dev/null )
      rss=$(rss_of "$pid")
      echo "$fw,$startup,$rss,$line" >> "$CSV"
    done
  done
  free_port
}

# ── Builds + corridas ──────────────────────────────────────────────────────
echo "== compilando apps (Maven) =="
for fw in javalin spring quarkus micronaut; do
  ( cd "$APPS/$fw" && mvn -q -DskipTests package ) || echo "  build $fw FALLÓ"
done

run_app javalin   "PORT=$PORT java -jar '$APPS/javalin/target/app.jar'"
run_app spring    "java -jar '$APPS/spring/target/app.jar' --server.port=$PORT"
run_app quarkus   "java -Dquarkus.http.port=$PORT -jar '$APPS/quarkus/target/quarkus-app/quarkus-run.jar'"
run_app micronaut "java -Dmicronaut.server.port=$PORT -jar '$APPS/micronaut/target/app.jar'"

# JxMVC: build WAR + Tomcat propio
( cd "$APPS/jxmvc/app" && mvn -q -DskipTests package ) || echo "  build jxmvc FALLÓ"
if setup_tomcat && [ -f "$APPS/jxmvc/app/target/ROOT.war" ]; then
  cp "$APPS/jxmvc/app/target/ROOT.war" "$WORK/tomcat/webapps/ROOT.war"
  run_app jxmvc "JAVA_OPTS='' '$WORK/tomcat/bin/catalina.sh' run"
else echo "  jxmvc: no se pudo preparar Tomcat/WAR"; fi

echo "CSV -> $CSV"

# ── Tabla (mediana de rps por framework/endpoint) ──────────────────────────
{
  echo "# Resultados nativos (bare-metal Linux, sin Docker)"
  echo
  echo "Host: $(uname -sr) · $(nproc) vCPU · puerto $PORT · conns=$CONNS dur=${DUR}s reps=$REPS."
  echo "Procesos JVM directos (sin contenedor). Mediana de $REPS repeticiones."
  echo
  echo "| Framework | Arranque (ms) | RSS (MB) | rps /plaintext | rps /json | errores |"
  echo "|---|---|---|---|---|---|"
  for fw in jxmvc spring quarkus micronaut javalin; do
    su=$(awk -F, -v f="$fw" '$1==f{print $2; exit}' "$CSV")
    rs=$(awk -F, -v f="$fw" '$1==f && $4~/plaintext/{print $3}' "$CSV" | sort -n | awk '{a[NR]=$1}END{print (NR?a[int((NR+1)/2)]:"-")}')
    pt=$(awk -F, -v f="$fw" '$1==f && $4~/plaintext/{print $10}' "$CSV" | sort -n | awk '{a[NR]=$1}END{print (NR?a[int((NR+1)/2)]:"-")}')
    js=$(awk -F, -v f="$fw" '$1==f && $4~/json/{print $10}' "$CSV" | sort -n | awk '{a[NR]=$1}END{print (NR?a[int((NR+1)/2)]:"-")}')
    er=$(awk -F, -v f="$fw" '$1==f{s+=$8}END{print s+0}' "$CSV")
    [ -n "$su" ] && echo "| $fw | $su | $rs | $pt | $js | $er |" || echo "| $fw | (no arrancó) | | | | |"
  done
} > "$OUT"
echo "Tabla -> $OUT"; cat "$OUT"
