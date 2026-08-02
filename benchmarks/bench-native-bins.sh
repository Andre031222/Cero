#!/usr/bin/env bash
# Corre binarios YA construidos (extraídos de las imágenes Docker, compilados con Java 17) de
# forma nativa en Linux, sin compilar aquí. Mide arranque, RSS y throughput/latencia.
# Espera los artefactos en $BINS: javalin-app.jar spring-app.jar micronaut-app.jar quarkus/ ROOT.war
#
# Uso: JAVA=/ruta/java PORT=8099 BINS=/root/bins ./bench-native-bins.sh [conns] [dur] [reps]
set -uo pipefail
JAVA="${JAVA:-java}"
PORT="${PORT:-8099}"
BINS="${BINS:-/root/bins}"
CONNS="${1:-48}"; DUR="${2:-15}"; REPS="${3:-3}"
TOMCAT_VER="${TOMCAT_VER:-10.1.31}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/tmp/jxbench}"; mkdir -p "$WORK"
CSV="$HERE/results/raw-server.csv"; OUT="$HERE/results/RESULTS-server.md"; mkdir -p "$HERE/results"
[ -f "$HERE/load/LoadClient.class" ] || (cd "$HERE/load" && javac LoadClient.java)

echo "framework,startup_ms,rss_mb,endpoint,conns,dur,requests,errors,non2xx,rps,meanMs,p50,p90,p95,p99" > "$CSV"
wait_up(){ local s n c; s=$(date +%s%3N); for _ in $(seq 1 600); do c=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/plaintext" 2>/dev/null||echo 000); [ "$c" = 200 ]&&{ n=$(date +%s%3N); echo $((n-s)); return 0;}; sleep 0.1; done; echo -1; return 1;}
pid_on_port(){ ss -ltnp "sport = :$PORT" 2>/dev/null|grep -oP 'pid=\K[0-9]+'|head -1;}
rss_of(){ [ -r "/proc/$1/status" ]&&awk '/VmRSS/{printf "%.1f",$2/1024}' "/proc/$1/status"||echo 0;}
free_port(){ local p; p=$(pid_on_port);[ -n "$p" ]&&kill "$p" 2>/dev/null; sleep 2; p=$(pid_on_port);[ -n "$p" ]&&kill -9 "$p" 2>/dev/null; sleep 1;}
setup_tomcat(){ local t="$WORK/tomcat"; if [ ! -x "$t/bin/catalina.sh" ]; then curl -sL "https://archive.apache.org/dist/tomcat/tomcat-10/v$TOMCAT_VER/bin/apache-tomcat-$TOMCAT_VER.tar.gz" -o "$WORK/t.tgz"||return 1; mkdir -p "$t"; tar xzf "$WORK/t.tgz" -C "$t" --strip-components=1||return 1; sed -i "s/port=\"8080\"/port=\"$PORT\"/" "$t/conf/server.xml"; fi; rm -rf "$t"/webapps/*; }

run_app(){ local fw="$1" cmd="$2" startup pid; echo "──────── $fw ────────"; free_port
  bash -c "$cmd" >"$WORK/$fw.log" 2>&1 & startup=$(wait_up)||startup=-1
  if [ "$startup" = -1 ]; then echo "  $fw NO arrancó:"; tail -10 "$WORK/$fw.log"; free_port; return; fi
  pid=$(pid_on_port); echo "  arranque=${startup}ms pid=$pid ($($JAVA -version 2>&1|head -1))"
  for ep in plaintext json; do for r in $(seq 1 "$REPS"); do
    local line rss; line=$( (cd "$HERE/load" && java LoadClient "http://127.0.0.1:$PORT/$ep" "$CONNS" "$DUR" 5) 2>/dev/null); rss=$(rss_of "$pid")
    echo "$fw,$startup,$rss,$line" >> "$CSV"; done; done
  free_port; }

run_app javalin   "PORT=$PORT '$JAVA' -jar '$BINS/javalin-app.jar'"
run_app spring    "'$JAVA' -jar '$BINS/spring-app.jar' --server.port=$PORT"
run_app quarkus   "'$JAVA' -Dquarkus.http.port=$PORT -jar '$BINS/quarkus/quarkus-run.jar'"
run_app micronaut "'$JAVA' -Dmicronaut.server.port=$PORT -jar '$BINS/micronaut-app.jar'"
if setup_tomcat; then cp "$BINS/ROOT.war" "$WORK/tomcat/webapps/ROOT.war"
  run_app jxmvc "JAVA_HOME='$(dirname "$(dirname "$JAVA")")' '$WORK/tomcat/bin/catalina.sh' run"
fi

{ echo "# Resultados nativos (bare-metal Linux, sin Docker)"; echo
  echo "Host: $(uname -sr) · $(nproc) vCPU · $($JAVA -version 2>&1|head -1) · puerto $PORT · conns=$CONNS dur=${DUR}s reps=$REPS."
  echo "Binarios pre-construidos (Java 17) ejecutados como procesos JVM directos. Mediana de $REPS reps."; echo
  echo "| Framework | Arranque (ms) | RSS (MB) | rps /plaintext | rps /json | errores |"; echo "|---|---|---|---|---|---|"
  for fw in jxmvc spring quarkus micronaut javalin; do
    su=$(awk -F, -v f="$fw" '$1==f{print $2;exit}' "$CSV")
    rs=$(awk -F, -v f="$fw" '$1==f&&$4~/plaintext/{print $3}' "$CSV"|sort -n|awk '{a[NR]=$1}END{print(NR?a[int((NR+1)/2)]:"-")}')
    pt=$(awk -F, -v f="$fw" '$1==f&&$4~/plaintext/{print $10}' "$CSV"|sort -n|awk '{a[NR]=$1}END{print(NR?a[int((NR+1)/2)]:"-")}')
    js=$(awk -F, -v f="$fw" '$1==f&&$4~/json/{print $10}' "$CSV"|sort -n|awk '{a[NR]=$1}END{print(NR?a[int((NR+1)/2)]:"-")}')
    er=$(awk -F, -v f="$fw" '$1==f{s+=$8}END{print s+0}' "$CSV")
    [ -n "$su" ]&&echo "| $fw | $su | $rs | $pt | $js | $er |"||echo "| $fw | (no arrancó) | | | | |"
  done; } > "$OUT"
echo "Tabla -> $OUT"; cat "$OUT"
