#!/bin/sh
# Instalador de Cero para macOS y Linux.
#
#   curl -fsSL https://cero.ginit.dev/instalar | sh
#
# Baja el paquete, comprueba su huella, lo compila, deja los artefactos en ~/.m2 y la orden
# `cero` en el PATH. No pide contraseña y no escribe fuera de $HOME.
#
#   --con-pruebas   corre las 1 830 pruebas durante la instalación (~90 s más)
#   --sin-color     salida plana, para registros y CI
set -eu

BASE="${CERO_BASE:-https://cero.ginit.dev}"
RAIZ="${CERO_HOME:-$HOME/.cero}"
BIN="${CERO_BIN:-$HOME/.local/bin}"
PRUEBAS=no

for arg in "$@"; do
  case "$arg" in
    --con-pruebas) PRUEBAS=si ;;
    --sin-color)   NO_COLOR=1 ;;
    -h|--ayuda|--help)
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
  esac
done

# ─── pintura ────────────────────────────────────────────────────────────────────────────
# Cuando esto va por una tubería la salida sigue siendo la terminal, así que la animación
# vale igual. Si no lo es —CI, un fichero de registro— se apaga sola.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != "dumb" ]; then
  ACENTO='\033[38;5;205m'; TENUE='\033[38;5;245m'; VERDE='\033[38;5;71m'
  ROJO='\033[38;5;167m';  FUERTE='\033[1m';       FIN='\033[0m'
  OCULTA='\033[?25l';     MUESTRA='\033[?25h';    BORRA='\r\033[K'
  VIVO=si
else
  ACENTO=''; TENUE=''; VERDE=''; ROJO=''; FUERTE=''; FIN=''
  OCULTA=''; MUESTRA=''; BORRA=''
  VIVO=no
fi

p() { printf "$@"; }

marca() {
  [ "$VIVO" = si ] || { p "Cero · instalador\n\n"; return; }
  p "\n"
  p "        ${ACENTO}·${FIN}   ${ACENTO}|${FIN}   ${ACENTO}·${FIN}\n"
  p "   ${ACENTO}\\${FIN}    ${ACENTO}·${FIN}     ${ACENTO}·${FIN}    ${ACENTO}/${FIN}\n"
  p " ${ACENTO}—${FIN}   ${ACENTO}·${FIN}   ${ACENTO}${FUERTE}███${FIN}   ${ACENTO}·${FIN}   ${ACENTO}—${FIN}      ${FUERTE}Cero${FIN}\n"
  p "   ${ACENTO}/${FIN}    ${ACENTO}·${FIN}     ${ACENTO}·${FIN}    ${ACENTO}\\${FIN}      ${TENUE}framework web para Java${FIN}\n"
  p "        ${ACENTO}·${FIN}   ${ACENTO}|${FIN}   ${ACENTO}·${FIN}\n\n"
}

PASO=0
paso() {
  PASO=$((PASO + 1))
  # Sin terminal no se puede volver atrás sobre la línea, así que no se escribe y ya la
  # pinta entera el ✓ de después.
  [ "$VIVO" = si ] && p "  ${ACENTO}%02d${FIN}  %s" "$PASO" "$1"
  return 0
}
bien()  { p "${BORRA}  ${VERDE}✓${FIN}   %s${TENUE}%s${FIN}\n" "$1" "${2:+  $2}"; }
mal()   { p "${BORRA}  ${ROJO}✗   %s${FIN}\n" "$1" >&2; }

# Muere el proceso hijo si nos cortan a mitad, para no dejar un mvn suelto.
HIJO=
limpiar() { p "${MUESTRA}"; [ -n "$HIJO" ] && kill "$HIJO" 2>/dev/null || true; }
trap limpiar EXIT INT TERM

# Corre una orden larga enseñando un giro y el tiempo que lleva. La salida va a un fichero:
# si acaba bien no se enseña, y si falla se enseña entera.
girando() {
  etiqueta="$1"; registro="$2"; shift 2
  inicio=$(date +%s)
  if [ "$VIVO" = no ]; then
    "$@" >"$registro" 2>&1 || return 1
    SEGUNDOS=$(( $(date +%s) - inicio ))
    return 0
  fi
  "$@" >"$registro" 2>&1 &
  HIJO=$!
  giros='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
  i=0
  p "${OCULTA}"
  while kill -0 "$HIJO" 2>/dev/null; do
    i=$((i + 1))
    giro=$(printf '%s' "$giros" | cut -c $(( (i % 10) + 1 )))
    p "${BORRA}  ${ACENTO}%s${FIN}   %s ${TENUE}%ss${FIN}" "$giro" "$etiqueta" "$(( $(date +%s) - inicio ))"
    sleep 0.08
  done
  wait "$HIJO"; estado=$?
  HIJO=
  p "${MUESTRA}"
  SEGUNDOS=$(( $(date +%s) - inicio ))
  return $estado
}

muere() {
  mal "$1"
  [ -n "${2:-}" ] && [ -f "$2" ] && { p "\n${TENUE}"; tail -25 "$2"; p "${FIN}\n"; }
  exit 1
}

# ─── 1 · lo que hace falta ──────────────────────────────────────────────────────────────
marca
paso "comprobando el entorno"

falta=
for orden in curl tar java mvn; do
  command -v "$orden" >/dev/null 2>&1 || falta="$falta $orden"
done
if [ -n "$falta" ]; then
  mal "falta:$falta"
  p "\n  Cero necesita un ${FUERTE}JDK 25${FIN} o superior y ${FUERTE}Maven${FIN}.\n"
  case "$(uname -s)" in
    Darwin) p "  ${TENUE}brew install openjdk@25 maven${FIN}\n" ;;
    Linux)  p "  ${TENUE}sudo apt install openjdk-25-jdk maven${FIN}   ${TENUE}(o el gestor de tu distribución)${FIN}\n" ;;
  esac
  exit 1
fi

JAVA_V=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ "${JAVA_V:-0}" -lt 25 ] 2>/dev/null; then
  muere "Cero necesita Java 25 o superior — hilos virtuales. Tienes $JAVA_V."
fi
bien "entorno" "Java $JAVA_V · $(mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3) · $(uname -s) $(uname -m)"

# ─── 2 · qué versión ────────────────────────────────────────────────────────────────────
paso "consultando la versión"
VERSION=$(curl -fsSL --max-time 20 "$BASE/version" 2>/dev/null) || \
  muere "no se pudo hablar con $BASE — ¿hay conexión?"
case "$VERSION" in
  ''|*[!0-9.]*) muere "el servidor devolvió una versión rara: '$VERSION'" ;;
esac
bien "versión" "Cero $VERSION"

# ─── 3 · bajarlo ────────────────────────────────────────────────────────────────────────
PAQUETE="cero-$VERSION.tar.gz"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/cero.XXXXXX")
trap 'limpiar; rm -rf "$TMP"' EXIT INT TERM

paso "bajando el paquete"
girando "bajando $PAQUETE" "$TMP/curl.log" \
  curl -fsSL --max-time 300 -o "$TMP/$PAQUETE" "$BASE/estaticos/$PAQUETE" \
  || muere "no se pudo bajar $BASE/estaticos/$PAQUETE" "$TMP/curl.log"
KB=$(( $(wc -c < "$TMP/$PAQUETE") / 1024 ))
bien "descargado" "$PAQUETE · ${KB} KB"

# ─── 4 · comprobar la huella ────────────────────────────────────────────────────────────
paso "comprobando la huella"
ESPERADA=$(curl -fsSL --max-time 20 "$BASE/estaticos/$PAQUETE.sha256" 2>/dev/null | cut -d' ' -f1) \
  || muere "no se pudo bajar la huella"
if command -v shasum >/dev/null 2>&1; then
  REAL=$(shasum -a 256 "$TMP/$PAQUETE" | cut -d' ' -f1)
elif command -v sha256sum >/dev/null 2>&1; then
  REAL=$(sha256sum "$TMP/$PAQUETE" | cut -d' ' -f1)
else
  REAL=''
fi
if [ -z "$REAL" ]; then
  p "${BORRA}  ${ACENTO}!${FIN}   huella ${TENUE}sin comprobar: no hay shasum ni sha256sum${FIN}\n"
elif [ "$REAL" != "$ESPERADA" ]; then
  muere "la huella no coincide — el paquete llegó cambiado, no lo instalo.
      esperada  $ESPERADA
      recibida  $REAL"
else
  bien "huella" "sha256 $(printf '%s' "$REAL" | cut -c1-16)…"
fi

# ─── 5 · extraer ────────────────────────────────────────────────────────────────────────
paso "extrayendo"
DESTINO="$RAIZ/cero-$VERSION"
mkdir -p "$RAIZ"
rm -rf "$DESTINO"
tar -xzf "$TMP/$PAQUETE" -C "$RAIZ" || muere "el paquete no se pudo extraer"
[ -d "$DESTINO" ] || muere "el paquete no traía cero-$VERSION dentro"
bien "extraído" "$DESTINO"

# ─── 6 · compilar ───────────────────────────────────────────────────────────────────────
paso "compilando"
if [ "$PRUEBAS" = si ]; then
  girando "compilando los ocho módulos y corriendo las pruebas" "$TMP/mvn.log" \
    mvn -B -q -f "$DESTINO/java/pom.xml" install \
    || muere "la compilación falló" "$TMP/mvn.log"
  bien "compilado" "con las pruebas en verde · ${SEGUNDOS:-?} s"
else
  girando "compilando los ocho módulos" "$TMP/mvn.log" \
    mvn -B -q -f "$DESTINO/java/pom.xml" -DskipTests install \
    || muere "la compilación falló" "$TMP/mvn.log"
  bien "compilado" "ocho módulos en ~/.m2 · ${SEGUNDOS:-?} s"
fi

# ─── 7 · dejar la orden a mano ──────────────────────────────────────────────────────────
paso "instalando la orden cero"
ln -sfn "$DESTINO" "$RAIZ/actual"
mkdir -p "$BIN"
cat > "$BIN/cero" <<GUION
#!/bin/sh
# Generado por el instalador de Cero. Apunta siempre a la versión en uso.
exec "$RAIZ/actual/cero" "\$@"
GUION
chmod +x "$BIN/cero"
bien "orden cero" "$BIN/cero"

# ─── 8 · comprobar que sirve ────────────────────────────────────────────────────────────
paso "comprobando la instalación"
"$BIN/cero" estado >/dev/null 2>&1 || muere "quedó instalado pero 'cero status' no responde"
bien "comprobado" "cero status responde"

# ─── final ──────────────────────────────────────────────────────────────────────────────
p "\n  ${VERDE}${FUERTE}Cero $VERSION instalado${FIN}\n\n"

case ":$PATH:" in
  *":$BIN:"*) ;;
  *)
    p "  ${ACENTO}Falta un paso${FIN} — $BIN no está en tu PATH. Añade esta línea a tu\n"
    p "  ${TENUE}~/.zshrc${FIN} o ${TENUE}~/.bashrc${FIN} y abre una terminal nueva:\n\n"
    p "      ${FUERTE}export PATH=\"%s:\$PATH\"${FIN}\n\n" "$(printf '%s' "$BIN" | sed "s|^$HOME|\$HOME|")" ;;
esac

p "  ${TENUE}Crear un proyecto y arrancarlo:${FIN}\n\n"
p "      ${FUERTE}cero new mi-app${FIN}\n"
p "      ${FUERTE}cd mi-app && mvn -q package && java -jar target/mi-app.jar${FIN}\n\n"
p "  ${TENUE}Guía completa:${FIN}  $BASE/empezar\n\n"
