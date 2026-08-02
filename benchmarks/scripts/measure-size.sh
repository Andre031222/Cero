#!/usr/bin/env bash
# Tamaño de los artefactos desplegables (bytes y KB/MB legible).
# Pásale las rutas de cada artefacto a medir; sin args, mide los de JxMVC del repo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

fmt() { awk -v b="$1" 'BEGIN{ if(b<1048576) printf "%.0f KB", b/1024; else printf "%.1f MB", b/1048576 }'; }
show() { [ -f "$1" ] && printf "%-46s %10d bytes  (%s)\n" "$(basename "$1")" "$(wc -c <"$1")" "$(fmt "$(wc -c <"$1")")" || echo "no encontrado: $1"; }

if [ "$#" -gt 0 ]; then
  for f in "$@"; do show "$f"; done
  exit 0
fi

echo "== JxMVC (framework solo) =="
show "$ROOT/JxMVC.Core/target/jxmvc-core-3.4.0.jar"
echo
echo "== JxMVC (WAR de la app demo, sin el container) =="
show "$ROOT/JxMVC2x/target/jxmvc.war"
echo
echo "Recordatorio: para el tamaño DESPLEGABLE justo, sumar el servlet container"
echo "(Tomcat mínimo ~ pocos MB) al artefacto de JxMVC, y compararlo con el uber-JAR"
echo "del rival que YA incluye su servidor embebido (ver README §2)."
