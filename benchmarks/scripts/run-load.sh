#!/usr/bin/env bash
# Mide throughput/latencia con el generador JDK. Repite N veces y reporta cada corrida en CSV.
# uso: run-load.sh <baseUrl> <conexiones> <segundos> [repeticiones]
set -euo pipefail
BASE="${1:?baseUrl requerido (ej http://localhost:8080)}"
CONNS="${2:-64}"
DUR="${3:-30}"
REPS="${4:-5}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

[ -f "$HERE/load/LoadClient.class" ] || (cd "$HERE/load" && javac LoadClient.java)

echo "endpoint,conns,durSecs,requests,errors,non2xx,rps,meanMs,p50,p90,p95,p99"
for ep in plaintext json; do
  for r in $(seq 1 "$REPS"); do
    (cd "$HERE/load" && java LoadClient "$BASE/$ep" "$CONNS" "$DUR" 5) 2>/dev/null
  done
done
