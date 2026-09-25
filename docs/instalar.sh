#!/bin/sh
# Instalador de Cero para macOS y Linux.
#
#   curl -fsSL https://cero.ginit.dev/instalar | sh
#
# Detecta el sistema, la arquitectura y el gestor de paquetes; baja el paquete, comprueba su
# huella, lo compila, deja los artefactos en ~/.m2 y la orden `cero` en el PATH. No pide
# contraseña y no escribe fuera de $HOME.
#
#   --con-pruebas   corre las pruebas durante la instalación (~90 s más)
#   --sin-color     salida plana, para registros y CI
#   --detectar      enseña qué sistema, arquitectura y Java ve, y termina
set -eu

BASE="${CERO_BASE:-https://cero.ginit.dev}"
RAIZ="${CERO_HOME:-$HOME/.cero}"
BIN="${CERO_BIN:-$HOME/.local/bin}"
JAVA_MINIMO=25
TOTAL=8
PRUEBAS=no
SOLO_DETECTAR=no

for arg in "$@"; do
  case "$arg" in
    --con-pruebas) PRUEBAS=si ;;
    --sin-color)   NO_COLOR=1 ;;
    --detectar)    SOLO_DETECTAR=si ;;
    -h|--ayuda|--help)
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
  esac
done

# ─── pintura ────────────────────────────────────────────────────────────────────────────
# Sin terminal, con TERM=dumb, con NO_COLOR o dentro de integración continua no se escribe
# ni un escape: un registro de CI lleno de restos de spinner no sirve para leer nada.
EN_CI=no
for v in CI GITHUB_ACTIONS GITLAB_CI JENKINS_URL BUILDKITE TEAMCITY_VERSION TF_BUILD; do
  eval "val=\${$v:-}"
  [ -n "$val" ] && EN_CI=si && break
done

if [ -t 1 ] && [ "$EN_CI" = no ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != "dumb" ]; then
  VIVO=si
  # El acento es el azul de la marca (#38bdf8). En 256 colores no existe, así que se usa el
  # más cercano; con truecolor va el exacto.
  case "${COLORTERM:-}" in
    truecolor|24bit) ACENTO='\033[38;2;56;189;248m' ;;
    *)               ACENTO='\033[38;5;75m' ;;
  esac
  TENUE='\033[38;5;245m'; VERDE='\033[38;5;71m'
  ROJO='\033[38;5;167m';   FUERTE='\033[1m';       FIN='\033[0m'
  OCULTA='\033[?25l';      MUESTRA='\033[?25h';    BORRA='\r\033[K'
else
  VIVO=no
  ACENTO=''; TENUE=''; VERDE=''; ROJO=''; FUERTE=''; FIN=''
  OCULTA=''; MUESTRA=''; BORRA=''
fi

# Los glifos solo si la configuración regional es UTF-8; si no, una tty de Linux con LANG=C
# los pinta como basura.
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
  *UTF-8*|*utf-8*|*UTF8*|*utf8*)
    GIROS='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'; NGIROS=10; LLENO='━'; VACIO='─'; OK='✓'; NO='✗' ;;
  *)
    GIROS='|/-\'; NGIROS=4; LLENO='#'; VACIO='-'; OK='+'; NO='x' ;;
esac

p() { printf "$@"; }

PASO=0
ETIQUETA=

barra() {
  ancho=16; i=0; hechos=$(( PASO * ancho / TOTAL )); b=''
  while [ "$i" -lt "$ancho" ]; do
    if [ "$i" -lt "$hechos" ]; then b="$b$LLENO"; else b="$b$VACIO"; fi
    i=$((i + 1))
  done
  printf '%s' "$b"
}

# En vivo la línea se reescribe sobre sí misma; en plano se imprime una vez y ya está.
paso() {
  PASO=$((PASO + 1))
  ETIQUETA="$1"
  if [ "$VIVO" = si ]; then
    p "${BORRA}  ${ACENTO}%s${FIN} ${TENUE}%d/%d${FIN}  %s" "$(barra)" "$PASO" "$TOTAL" "$1"
  else
    p "[%d/%d] %s\n" "$PASO" "$TOTAL" "$1"
  fi
}

# %-28s cuenta bytes, así que con acentos la columna se descuadra: se rellena a mano.
rellena() {
  n=$(printf '%s' "$1" | wc -m | tr -d ' ')
  printf '%s' "$1"
  while [ "$n" -lt "$2" ]; do printf ' '; n=$((n + 1)); done
}

bien() {
  if [ "$VIVO" = si ]; then
    p "${BORRA}  ${VERDE}%s${FIN}  %s${TENUE}%s${FIN}\n" "$OK" "$(rellena "$1" 26)" "${2:-}"
  else
    p "        %s %s\n" "$1" "${2:-}"
  fi
}

aviso() { p "${BORRA}  ${ACENTO}!${FIN}  %s${TENUE}%s${FIN}\n" "$(rellena "$1" 26)" "${2:-}"; }
mal()   { p "${BORRA}  ${ROJO}%s  %s${FIN}\n" "$NO" "$1" >&2; }

# El cursor vuelve siempre, también si cortan con Ctrl-C a mitad del giro.
HIJO=
TMP=
limpiar() {
  p "${MUESTRA}"
  [ -n "$HIJO" ] && kill "$HIJO" 2>/dev/null || true
  [ -n "$TMP" ] && rm -rf "$TMP" || true
}
trap limpiar EXIT
trap 'limpiar; exit 130' INT
trap 'limpiar; exit 143' TERM HUP

# Corre una orden larga. La salida va a un fichero: si acaba bien no se enseña, y si falla
# se enseña entera.
girando() {
  registro="$1"; shift
  inicio=$(date +%s)
  if [ "$VIVO" = no ]; then
    "$@" >"$registro" 2>&1 || return 1
    SEGUNDOS=$(( $(date +%s) - inicio ))
    return 0
  fi
  "$@" >"$registro" 2>&1 &
  HIJO=$!
  i=0
  p "${OCULTA}"
  while kill -0 "$HIJO" 2>/dev/null; do
    i=$((i + 1))
    giro=$(printf '%s' "$GIROS" | cut -c $(( (i % NGIROS) + 1 )))
    p "${BORRA}  ${ACENTO}%s${FIN} ${TENUE}%d/%d${FIN}  %s ${ACENTO}%s${FIN} ${TENUE}%ss${FIN}" \
      "$(barra)" "$PASO" "$TOTAL" "$ETIQUETA" "$giro" "$(( $(date +%s) - inicio ))"
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

marca() {
  if [ "$VIVO" = no ]; then
    p "Cero - instalador\n\n"
    return
  fi
  p "\n"
  p "        ${ACENTO}·${FIN}   ${ACENTO}|${FIN}   ${ACENTO}·${FIN}\n"
  p "   ${ACENTO}\\\\${FIN}    ${ACENTO}·${FIN}     ${ACENTO}·${FIN}    ${ACENTO}/${FIN}\n"
  p " ${ACENTO}—${FIN}   ${ACENTO}·${FIN}   ${ACENTO}${FUERTE}███${FIN}   ${ACENTO}·${FIN}   ${ACENTO}—${FIN}      ${FUERTE}Cero${FIN}\n"
  p "   ${ACENTO}/${FIN}    ${ACENTO}·${FIN}     ${ACENTO}·${FIN}    ${ACENTO}\\\\${FIN}      ${TENUE}framework web para Java${FIN}\n"
  p "        ${ACENTO}·${FIN}   ${ACENTO}|${FIN}   ${ACENTO}·${FIN}\n\n"
}

# ─── detección ──────────────────────────────────────────────────────────────────────────
NUCLEO=$(uname -s)
case "$(uname -m)" in
  arm64|aarch64)        ARQ=arm64 ;;
  x86_64|amd64)         ARQ=x86_64 ;;
  armv7*|armv6*|armhf)  ARQ=arm32 ;;
  riscv64)              ARQ=riscv64 ;;
  ppc64le|ppc64)        ARQ=ppc64 ;;
  s390x)                ARQ=s390x ;;
  *)                    ARQ=$(uname -m) ;;
esac

case "$NUCLEO" in
  Darwin)
    SISTEMA=macOS
    VERSION_SO=$(sw_vers -productVersion 2>/dev/null || echo '')
    case "$ARQ" in
      arm64) MARCA_CPU='Apple Silicon' ;;
      *)     MARCA_CPU='Intel' ;;
    esac
    DETALLE_SO="macOS ${VERSION_SO:-?} · $MARCA_CPU ($ARQ)" ;;
  Linux)
    SISTEMA=Linux
    # `.` es un builtin especial: si falla con `set -e` mata el intérprete pese al `||`.
    DISTRO=Linux
    [ -r /etc/os-release ] && DISTRO=$(sed -n 's/^PRETTY_NAME="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' /etc/os-release | head -1)
    [ -n "$DISTRO" ] || DISTRO=Linux
    DETALLE_SO="$DISTRO · $ARQ"
    grep -qi microsoft /proc/version 2>/dev/null && DETALLE_SO="$DETALLE_SO · WSL" ;;
  MINGW*|MSYS*|CYGWIN*)
    SISTEMA=Windows
    DETALLE_SO="Windows bajo $NUCLEO · $ARQ" ;;
  FreeBSD|OpenBSD|NetBSD|DragonFly)
    SISTEMA=BSD
    DETALLE_SO="$NUCLEO $(uname -r 2>/dev/null) · $ARQ" ;;
  SunOS)
    SISTEMA=Solaris
    DETALLE_SO="$(uname -v 2>/dev/null || echo SunOS) · $ARQ" ;;
  AIX)
    SISTEMA=AIX
    DETALLE_SO="AIX $(uname -v 2>/dev/null).$(uname -r 2>/dev/null) · $ARQ" ;;
  Haiku)
    SISTEMA=Haiku
    DETALLE_SO="Haiku · $ARQ" ;;
  *)
    SISTEMA="$NUCLEO"
    DETALLE_SO="$NUCLEO · $ARQ" ;;
esac

INTERPRETE=$(basename "${SHELL:-sh}")

GESTOR=
for g in brew port apt-get dnf yum pacman zypper apk emerge xbps-install nix-env pkg pkgin pkgutil winget scoop choco; do
  if command -v "$g" >/dev/null 2>&1; then GESTOR="$g"; break; fi
done

# La orden concreta para ESTE sistema, no una lista de posibilidades.
orden_java() {
  case "$GESTOR" in
    brew)    printf 'brew install openjdk@%s maven && sudo ln -sfn "$(brew --prefix)/opt/openjdk@%s/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-%s.jdk' "$JAVA_MINIMO" "$JAVA_MINIMO" "$JAVA_MINIMO" ;;
    apt-get) printf 'sudo apt-get install -y openjdk-%s-jdk maven' "$JAVA_MINIMO" ;;
    dnf)     printf 'sudo dnf install -y java-%s-openjdk-devel maven' "$JAVA_MINIMO" ;;
    pacman)  printf 'sudo pacman -S --needed jdk-openjdk maven' ;;
    zypper)  printf 'sudo zypper install -y java-%s-openjdk-devel maven' "$JAVA_MINIMO" ;;
    apk)     printf 'sudo apk add openjdk%s maven' "$JAVA_MINIMO" ;;
    winget)  printf 'winget install EclipseAdoptium.Temurin.%s.JDK Apache.Maven' "$JAVA_MINIMO" ;;
    scoop)   printf 'scoop install temurin%s-jdk maven' "$JAVA_MINIMO" ;;
    choco)   printf 'choco install -y temurin%s maven' "$JAVA_MINIMO" ;;
    port)    printf 'sudo port install openjdk%s-temurin maven' "$JAVA_MINIMO" ;;
    yum)     printf 'sudo yum install -y java-%s-openjdk-devel maven' "$JAVA_MINIMO" ;;
    emerge)  printf 'sudo emerge --ask dev-java/openjdk:%s dev-java/maven-bin' "$JAVA_MINIMO" ;;
    xbps-install) printf 'sudo xbps-install -S openjdk%s maven' "$JAVA_MINIMO" ;;
    nix-env) printf 'nix-env -iA nixpkgs.temurin-bin-%s nixpkgs.maven' "$JAVA_MINIMO" ;;
    pkg)     printf 'sudo pkg install -y openjdk%s maven' "$JAVA_MINIMO" ;;
    pkgin)   printf 'sudo pkgin -y install openjdk%s apache-maven' "$JAVA_MINIMO" ;;
    pkgutil) printf 'sudo pkgutil -i openjdk%s maven' "$JAVA_MINIMO" ;;
    *)
      case "$SISTEMA" in
        macOS) printf 'instala Homebrew (https://brew.sh) y luego: brew install openjdk@%s maven' "$JAVA_MINIMO" ;;
        *)     printf 'baja un JDK %s de https://adoptium.net/temurin/releases/?os=%s&arch=%s y Maven de https://maven.apache.org/download.cgi' \
                 "$JAVA_MINIMO" "$(printf '%s' "$SISTEMA" | tr 'A-Z' 'a-z')" "$ARQ" ;;
      esac ;;
  esac
}

version_java() {
  command -v java >/dev/null 2>&1 || { printf '0'; return; }
  java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/'
}

if [ "$SOLO_DETECTAR" = si ]; then
  marca
  jv=$(version_java)
  p "  %-14s %s\n" sistema  "$DETALLE_SO"
  p "  %-14s %s\n" terminal "$INTERPRETE"
  p "  %-14s %s\n" gestor   "${GESTOR:-ninguno conocido}"
  p "  %-14s %s\n" java     "$( [ "${jv:-0}" -gt 0 ] 2>/dev/null && echo "$jv" || echo 'no encontrado' )"
  p "  %-14s %s\n" maven    "$(command -v mvn >/dev/null 2>&1 && mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3 || echo 'no encontrado')"
  p "  %-14s %s\n" salida   "$( [ "$VIVO" = si ] && echo 'terminal con color' || echo 'plana (sin escapes)' )"
  if [ "${jv:-0}" -lt "$JAVA_MINIMO" ] 2>/dev/null; then
    p "\n  Para tener Java %s en tu sistema:\n\n      %s\n\n" "$JAVA_MINIMO" "$(orden_java)"
  fi
  exit 0
fi

# ─── 1 · lo que hace falta ──────────────────────────────────────────────────────────────
marca
paso "comprobando el entorno"

if [ "$SISTEMA" = Windows ]; then
  aviso "estás en Windows" "en PowerShell:  irm $BASE/instalar.ps1 | iex"
fi

falta=
for orden in curl tar java mvn; do
  command -v "$orden" >/dev/null 2>&1 || falta="$falta $orden"
done
if [ -n "$falta" ]; then
  mal "falta:$falta"
  p "\n  Cero necesita un ${FUERTE}JDK %s${FIN} o superior y ${FUERTE}Maven${FIN}.\n" "$JAVA_MINIMO"
  p "  ${TENUE}Detectado: %s · gestor %s${FIN}\n\n" "$DETALLE_SO" "${GESTOR:-ninguno}"
  p "      ${FUERTE}%s${FIN}\n\n" "$(orden_java)"
  exit 1
fi

JAVA_V=$(version_java)
if [ "${JAVA_V:-0}" -lt "$JAVA_MINIMO" ] 2>/dev/null; then
  mal "Cero necesita Java $JAVA_MINIMO o superior — hilos virtuales. Tienes ${JAVA_V:-ninguno}."
  p "\n  ${TENUE}Detectado: %s · gestor %s${FIN}\n\n" "$DETALLE_SO" "${GESTOR:-ninguno}"
  p "      ${FUERTE}%s${FIN}\n\n" "$(orden_java)"
  exit 1
fi
MAVEN_V=$(mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3)
bien "entorno" "$DETALLE_SO · Java $JAVA_V · $MAVEN_V"

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

paso "bajando el paquete"
girando "$TMP/curl.log" \
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
  aviso "huella" "sin comprobar: no hay shasum ni sha256sum"
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
if [ "$PRUEBAS" = si ]; then
  paso "compilando los ocho módulos y corriendo las pruebas"
  girando "$TMP/mvn.log" mvn -B -q -f "$DESTINO/java/pom.xml" install \
    || muere "la compilación falló" "$TMP/mvn.log"
  bien "compilado" "con las pruebas en verde · ${SEGUNDOS:-?} s"
else
  paso "compilando los ocho módulos"
  girando "$TMP/mvn.log" mvn -B -q -f "$DESTINO/java/pom.xml" -DskipTests install \
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

# ─── resumen ────────────────────────────────────────────────────────────────────────────
p "\n  ${VERDE}${FUERTE}Cero %s instalado${FIN}\n\n" "$VERSION"
p "  %-12s %s\n" sistema "$DETALLE_SO"
p "  %-12s %s\n" java    "$JAVA_V · $MAVEN_V"
p "  %-12s %s\n" carpeta "$DESTINO"
p "  %-12s %s\n" orden   "$BIN/cero"
p "\n"

case ":$PATH:" in
  *":$BIN:"*) ;;
  *)
    p "  ${ACENTO}Falta un paso${FIN} — %s no está en tu PATH. Añade esta línea a tu\n" "$BIN"
    case "$INTERPRETE" in
      zsh)  PERFIL='~/.zshrc' ;;
      fish) PERFIL='~/.config/fish/config.fish' ;;
      *)    PERFIL='~/.bashrc' ;;
    esac
    p "  ${TENUE}%s${FIN} y abre una terminal nueva:\n\n" "$PERFIL"
    p "      ${FUERTE}export PATH=\"%s:\$PATH\"${FIN}\n\n" "$(printf '%s' "$BIN" | sed "s|^$HOME|\$HOME|")" ;;
esac

p "  ${TENUE}Crear un proyecto y arrancarlo:${FIN}\n\n"
p "      ${FUERTE}cero new mi-app${FIN}\n"
p "      ${FUERTE}cd mi-app && mvn -q package && java -Xmx64m -jar target/mi-app.jar${FIN}\n\n"
p "  ${TENUE}Guía completa:${FIN}  %s/empezar\n\n" "$BASE"
