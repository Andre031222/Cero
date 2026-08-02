#!/usr/bin/env bash
# Hot-reload del frontend: copia JSP/CSS/JS/assets del source al webapp que
# Tomcat ya tiene desplegado. Tomcat recompila las JSP en caliente y sirve los
# estáticos al vuelo — no hace falta 'mvn package' ni reiniciar el contenedor.
#
#   Uso:  ./sync-front.sh        (una vez, tras editar el front)
#         ./sync-front.sh watch  (re-sincroniza cada 2 s automáticamente)
set -euo pipefail
cd "$(dirname "$0")"

SRC="src/main/webapp"
DEP="target/cargo/configurations/tomcat10x/webapps/jxmvc"

if [ ! -d "$DEP" ]; then
  echo "✗ No existe el webapp desplegado: $DEP"
  echo "  Levanta el sitio primero:  mvn -o package cargo:run -DskipTests=true"
  exit 1
fi

sync_once() {
  cp -r "$SRC/WEB-INF/views" "$DEP/WEB-INF/"
  cp    "$SRC/WEB-INF/jx.tld" "$DEP/WEB-INF/jx.tld"
  cp -r "$SRC/assets"         "$DEP/"
  echo "✓ front sincronizado → refresca el navegador (Ctrl+F5)"
}

if [ "${1:-}" = "watch" ]; then
  echo "▶ watch: sincronizando front cada 2 s (Ctrl+C para salir)"
  while true; do sync_once; sleep 2; done
else
  sync_once
fi
