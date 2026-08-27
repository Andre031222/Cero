#!/usr/bin/env bash
# Carga sostenida: horas de tráfico vigilando memoria y descriptores de archivo.
#
# Las pruebas hostiles del módulo son ráfagas de segundos — ahí no aparece una fuga. Esto manda
# tráfico continuo y muestrea RSS y descriptores abiertos cada poco. Lo que se busca no es un
# número alto, sino una recta plana: si el RSS o los descriptores suben sin parar, hay fuga.
#
#   ./carga-sostenida.sh [minutos] [conexiones]
set -euo pipefail

MINUTOS="${1:-30}"
CONNS="${2:-64}"
PUERTO="${CARGA_PUERTO:-18321}"
MUESTREO="${CARGA_MUESTREO:-15}"

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/.." && pwd)"
SALIDA="$AQUI/results/carga-sostenida.csv"

command -v java >/dev/null || { echo "falta java"; exit 1; }
if lsof -nP -iTCP:"$PUERTO" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "el puerto $PUERTO está ocupado; usa CARGA_PUERTO=<otro>"; exit 1
fi

echo "compilando…"
(cd "$REPO/java" && mvn -B -q -DskipTests install)
[ -f "$AQUI/load/LoadClient.class" ] || (cd "$AQUI/load" && javac LoadClient.java)

cp=$(ls -d "$REPO"/java/corvo-*/target/corvo-*.jar | tr '\n' ':')
tmp=$(mktemp -d)
cat > "$tmp/Sostenido.java" <<'JAVA'
import corvo.core.Lux;
import corvo.core.Result;

public class Sostenido {
    public static void main(String[] args) throws Exception {
        Lux.app().port(Integer.parseInt(args[0])).quiet()
           .routes(r -> r
               .get("/plaintext", ctx -> Result.text("OK"))
               .get("/json", ctx -> Result.raw("{\"mensaje\":\"hola\"}")))
           .start().await();
    }
}
JAVA
javac -cp "$cp" -d "$tmp" "$tmp/Sostenido.java"

java -Xmx512m -cp "$cp:$tmp" Sostenido "$PUERTO" &
SERVIDOR=$!
trap 'kill $SERVIDOR 2>/dev/null || true' EXIT

for _ in $(seq 1 100); do
  curl -s -o /dev/null -m 1 "http://127.0.0.1:$PUERTO/plaintext" && break
  sleep 0.2
done

echo "segundos,rss_mb,descriptores,hilos" > "$SALIDA"
echo "servidor pid=$SERVIDOR · $MINUTOS min · $CONNS conexiones · muestra cada ${MUESTREO}s"

fin=$(( $(date +%s) + MINUTOS * 60 ))
inicio=$(date +%s)

# El generador de carga corre en bucle; el bucle de muestreo vigila mientras tanto.
(
  while [ "$(date +%s)" -lt "$fin" ]; do
    (cd "$AQUI/load" && java LoadClient "http://127.0.0.1:$PUERTO/json" "$CONNS" "$MUESTREO" 0) >/dev/null 2>&1 || true
  done
) &
CARGA=$!

while [ "$(date +%s)" -lt "$fin" ]; do
  sleep "$MUESTREO"
  transcurrido=$(( $(date +%s) - inicio ))
  rss=$(ps -o rss= -p "$SERVIDOR" 2>/dev/null | tr -d ' ')
  [ -z "$rss" ] && { echo "el servidor se murió a los ${transcurrido}s"; exit 1; }
  fds=$(lsof -p "$SERVIDOR" 2>/dev/null | wc -l | tr -d ' ')
  hilos=$(ps -M "$SERVIDOR" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')
  printf '%s,%.1f,%s,%s\n' "$transcurrido" "$(echo "$rss / 1024" | bc -l)" "$fds" "$hilos" >> "$SALIDA"
  printf '  %5ss  rss=%6.1f MB  fd=%-5s hilos=%s\n' \
      "$transcurrido" "$(echo "$rss / 1024" | bc -l)" "$fds" "$hilos"
done

kill $CARGA 2>/dev/null || true
wait $CARGA 2>/dev/null || true

echo
echo "── veredicto ──"
awk -F, 'NR>1 { if (NR==2) { rss0=$2; fd0=$3 } rssN=$2; fdN=$3; n++ }
END {
  if (n < 3) { print "  muestras insuficientes"; exit }
  printf "  RSS:          %.1f → %.1f MB  (%+.1f)\n", rss0, rssN, rssN-rss0
  printf "  descriptores: %d → %d  (%+d)\n", fd0, fdN, fdN-fd0
  if (rssN > rss0 * 1.5) print "  ⚠ el RSS creció más de un 50 %: sospechoso"
  else print "  RSS estable"
  if (fdN > fd0 + 50) print "  ⚠ los descriptores no se devuelven: fuga"
  else print "  descriptores estables"
}' "$SALIDA"

echo "  detalle -> $SALIDA"
