#!/usr/bin/env bash
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO=https://github.com/Andre031222/Cero
TMP=$(mktemp -d "${TMPDIR:-/tmp}/cero-new.XXXXXX")
PID=""
fallos=0

for p in python3.14 python3.13 python3.12 python3; do
  if command -v "$p" >/dev/null && "$p" -c 'import sys; sys.exit(sys.version_info < (3, 12))'; then PY=$p; break; fi
done

limpiar() {
  [ -z "$PID" ] || kill "$PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap limpiar EXIT

puerto_libre() {
  "$PY" -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

construir() {
  case "$1" in
    java)   mvn -B -q package -DskipTests ;;
    rust)   grep -q "git = \"$REPO\"" Cargo.toml
            cargo build --release -q --config "patch.\"$REPO\".cero-http.path=\"$RAIZ/rust/cero-http\"" ;;
    c++)    grep -q "GIT_REPOSITORY $REPO" CMakeLists.txt
            cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DFETCHCONTENT_SOURCE_DIR_CERO="$RAIZ" >/dev/null
            cmake --build build -j >/dev/null ;;
    python) grep -q "git+$REPO#subdirectory=python" requirements.txt ;;
  esac
}

arrancar() {
  case "$1" in
    java)   exec java -jar "target/$2.jar" "$3" ;;
    rust)   PUERTO=$3 exec "./target/release/$2" ;;
    c++)    PUERTO=$3 exec ./build/hola ;;
    python) PUERTO=$3 PYTHONPATH="$RAIZ/python" exec "$PY" -B app.py ;;
  esac
}

probar() {
  local lenguaje=$1 nombre="demo-$1" puerto cuerpo="" i
  [ "$lenguaje" != c++ ] || nombre=demo-cxx
  (cd "$TMP" && "$RAIZ/cero" new "$nombre" --lenguaje "$lenguaje") >"$TMP/$nombre.log" 2>&1 || return 1
  (cd "$TMP/$nombre" && construir "$lenguaje") >>"$TMP/$nombre.log" 2>&1 || return 1
  puerto=$(puerto_libre)
  (cd "$TMP/$nombre" && arrancar "$lenguaje" "$nombre" "$puerto") >>"$TMP/$nombre.log" 2>&1 &
  PID=$!
  for i in $(seq 100); do
    cuerpo=$(curl -fsS "http://127.0.0.1:$puerto/" 2>/dev/null) && break
    kill -0 "$PID" 2>/dev/null || break
    sleep 0.2
  done
  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
  PID=""
  printf '%s\n' "$cuerpo" >>"$TMP/$nombre.log"
  case "$cuerpo" in
    '{"hola":"mundo"}'|*'"estado":"funcionando"'*) return 0 ;;
    *) return 1 ;;
  esac
}

for lenguaje in ${*:-java rust c++ python}; do
  if probar "$lenguaje"; then
    echo "  ok cero new --lenguaje $lenguaje"
  else
    echo "  x  cero new --lenguaje $lenguaje"
    tail -n 15 "$TMP"/demo-*"${lenguaje/++/xx}.log" | sed 's/^/     /'
    fallos=$((fallos + 1))
  fi
done
exit $((fallos > 0))
