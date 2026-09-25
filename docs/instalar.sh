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

# ── Idioma ──────────────────────────────────────────────────────────────────────────────────
#
# El instalador es lo primero que alguien ve de Cero, y hasta ahora solo hablaba castellano.
# `CERO_LANG` manda sobre el entorno, para forzarlo en un guion o en integración continua.
case "${CERO_LANG:-${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}}" in
  es*|ES*) IDIOMA=es ;;
  *)       IDIOMA=en ;;
esac

# Sin traducción cae al castellano: un hueco tiene que verse raro, no quedarse en blanco.
t() {
  v=""
  [ "$IDIOMA" = en ] && v=$(texto_en "$1")
  [ -z "$v" ] && v=$(texto_es "$1")
  [ -z "$v" ] && v="$1"
  printf '%s' "$v"
}

texto_es() {
  case "$1" in
    p-entorno)     printf '%s' 'comprobando el entorno' ;;
    p-version)     printf '%s' 'consultando la versión' ;;
    p-bajando)     printf '%s' 'bajando el paquete' ;;
    p-huella)      printf '%s' 'comprobando la huella' ;;
    p-extrayendo)  printf '%s' 'extrayendo' ;;
    p-compilando)  printf '%s' 'compilando los ocho módulos' ;;
    p-compilando-t) printf '%s' 'compilando los ocho módulos y corriendo las pruebas' ;;
    p-instalando)  printf '%s' 'instalando la orden cero' ;;
    p-comprobando) printf '%s' 'comprobando la instalación' ;;
    b-entorno)     printf '%s' 'entorno' ;;
    b-version)     printf '%s' 'versión' ;;
    b-descargado)  printf '%s' 'descargado' ;;
    b-huella)      printf '%s' 'huella' ;;
    b-extraido)    printf '%s' 'extraído' ;;
    b-compilado)   printf '%s' 'compilado' ;;
    b-orden)       printf '%s' 'orden cero' ;;
    b-comprobado)  printf '%s' 'comprobado' ;;
    b-responde)    printf '%s' 'cero status responde' ;;
    e-sin-red)     printf '%s' 'no se pudo hablar con %s — ¿hay conexión?' ;;
    e-version)     printf '%s' 'el servidor devolvió una versión rara' ;;
    e-descarga)    printf '%s' 'no se pudo bajar %s' ;;
    e-sin-huella)  printf '%s' 'no se pudo bajar la huella' ;;
    e-extraer)     printf '%s' 'el paquete no se pudo extraer' ;;
    e-contenido)   printf '%s' 'el paquete no traía %s dentro' ;;
    e-compilar)    printf '%s' 'la compilación falló' ;;
    e-no-responde) printf '%s' 'quedó instalado pero "cero status" no responde' ;;
    a-windows)     printf '%s' 'estás en Windows' ;;
    a-sin-sha)     printf '%s' 'sin comprobar: no hay shasum ni sha256sum' ;;
    f-instalado)   printf '%s' 'Cero %s instalado' ;;
    f-sistema)     printf '%s' 'sistema' ;;
    f-carpeta)     printf '%s' 'carpeta' ;;
    f-orden)       printf '%s' 'orden' ;;
    f-crear)       printf '%s' 'Crear un proyecto y arrancarlo:' ;;
    f-guia)        printf '%s' 'Guía completa:' ;;
    f-falta-paso)  printf '%s' 'Falta un paso' ;;
    d-lema)        printf '%s' 'framework web para Java' ;;
    f-instalador)  printf '%s' 'Cero - instalador' ;;
    f-terminal)    printf '%s' 'terminal' ;;
    f-gestor)      printf '%s' 'gestor' ;;
    f-salida)      printf '%s' 'salida' ;;
    f-ninguno)     printf '%s' 'ninguno conocido' ;;
    f-no-hay)      printf '%s' 'no encontrado' ;;
    f-color)       printf '%s' 'terminal con color' ;;
    f-plana)       printf '%s' 'plana (sin escapes)' ;;
    f-para-java)   printf '%s' 'Para tener Java %s en tu sistema:' ;;
    e-falta)       printf '%s' 'falta:%s' ;;
    t-necesita)    printf '%s' 'Cero necesita un JDK %s o superior y Maven.' ;;
    t-detectado)   printf '%s' 'Detectado: %s · gestor %s' ;;
    t-ninguno)     printf '%s' 'ninguno' ;;
    e-java-viejo)  printf '%s' 'Cero necesita Java %s o superior — hilos virtuales. Tienes %s.' ;;
    e-huella-mal)  printf '%s' 'la huella no coincide — el paquete llegó cambiado, no lo instalo.' ;;
    e-esperada)    printf '%s' 'esperada' ;;
    e-recibida)    printf '%s' 'recibida' ;;
    f-path)        printf '%s' '%s no está en tu PATH. Añade esta línea a tu' ;;
    f-perfil)      printf '%s' '%s y abre una terminal nueva:' ;;
    f-java)        printf '%s' 'java' ;;
    f-maven)       printf '%s' 'maven' ;;
    d-modulos)     printf '%s' 'ocho módulos en ~/.m2 · %s s' ;;
    d-responde)    printf '%s' 'cero status responde' ;;
    *)             printf '%s' '' ;;
  esac
}

texto_en() {
  case "$1" in
    p-entorno)     printf '%s' 'checking the environment' ;;
    p-version)     printf '%s' 'asking for the version' ;;
    p-bajando)     printf '%s' 'downloading the package' ;;
    p-huella)      printf '%s' 'verifying the checksum' ;;
    p-extrayendo)  printf '%s' 'extracting' ;;
    p-compilando)  printf '%s' 'building the eight modules' ;;
    p-compilando-t) printf '%s' 'building the eight modules and running the tests' ;;
    p-instalando)  printf '%s' 'installing the cero command' ;;
    p-comprobando) printf '%s' 'checking the installation' ;;
    b-entorno)     printf '%s' 'environment' ;;
    b-version)     printf '%s' 'version' ;;
    b-descargado)  printf '%s' 'downloaded' ;;
    b-huella)      printf '%s' 'checksum' ;;
    b-extraido)    printf '%s' 'extracted' ;;
    b-compilado)   printf '%s' 'built' ;;
    b-orden)       printf '%s' 'cero command' ;;
    b-comprobado)  printf '%s' 'checked' ;;
    b-responde)    printf '%s' 'cero status answers' ;;
    e-sin-red)     printf '%s' 'could not reach %s — is there a connection?' ;;
    e-version)     printf '%s' 'the server returned an odd version' ;;
    e-descarga)    printf '%s' 'could not download %s' ;;
    e-sin-huella)  printf '%s' 'could not download the checksum' ;;
    e-extraer)     printf '%s' 'the package could not be extracted' ;;
    e-contenido)   printf '%s' 'the package did not contain %s' ;;
    e-compilar)    printf '%s' 'the build failed' ;;
    e-no-responde) printf '%s' 'it installed but "cero status" does not answer' ;;
    a-windows)     printf '%s' 'you are on Windows' ;;
    a-sin-sha)     printf '%s' 'not verified: no shasum or sha256sum available' ;;
    f-instalado)   printf '%s' 'Cero %s installed' ;;
    f-sistema)     printf '%s' 'system' ;;
    f-carpeta)     printf '%s' 'directory' ;;
    f-orden)       printf '%s' 'command' ;;
    f-crear)       printf '%s' 'Create a project and start it:' ;;
    f-guia)        printf '%s' 'Full guide:' ;;
    f-falta-paso)  printf '%s' 'One step left' ;;
    d-lema)        printf '%s' 'web framework for Java' ;;
    f-instalador)  printf '%s' 'Cero - installer' ;;
    f-terminal)    printf '%s' 'shell' ;;
    f-gestor)      printf '%s' 'manager' ;;
    f-salida)      printf '%s' 'output' ;;
    f-ninguno)     printf '%s' 'none known' ;;
    f-no-hay)      printf '%s' 'not found' ;;
    f-color)       printf '%s' 'terminal with colour' ;;
    f-plana)       printf '%s' 'plain (no escapes)' ;;
    f-para-java)   printf '%s' 'To get Java %s on your system:' ;;
    e-falta)       printf '%s' 'missing:%s' ;;
    t-necesita)    printf '%s' 'Cero needs a JDK %s or newer and Maven.' ;;
    t-detectado)   printf '%s' 'Detected: %s · package manager %s' ;;
    t-ninguno)     printf '%s' 'none' ;;
    e-java-viejo)  printf '%s' 'Cero needs Java %s or newer — virtual threads. You have %s.' ;;
    e-huella-mal)  printf '%s' 'the checksum does not match — the package arrived altered, not installing it.' ;;
    e-esperada)    printf '%s' 'expected' ;;
    e-recibida)    printf '%s' 'received' ;;
    f-path)        printf '%s' '%s is not on your PATH. Add this line to your' ;;
    f-perfil)      printf '%s' '%s and open a new terminal:' ;;
    f-java)        printf '%s' 'java' ;;
    f-maven)       printf '%s' 'maven' ;;
    d-modulos)     printf '%s' 'eight modules in ~/.m2 · %s s' ;;
    d-responde)    printf '%s' 'cero status answers' ;;
    *)             printf '%s' '' ;;
  esac
}

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
    p "%s\n\n" "$(t f-instalador)"
    return
  fi
  p "\n"
  p "        ${ACENTO}·${FIN}   ${ACENTO}|${FIN}   ${ACENTO}·${FIN}\n"
  p "   ${ACENTO}\\\\${FIN}    ${ACENTO}·${FIN}     ${ACENTO}·${FIN}    ${ACENTO}/${FIN}\n"
  p " ${ACENTO}—${FIN}   ${ACENTO}·${FIN}   ${ACENTO}${FUERTE}███${FIN}   ${ACENTO}·${FIN}   ${ACENTO}—${FIN}      ${FUERTE}Cero${FIN}\n"
  p "   ${ACENTO}/${FIN}    ${ACENTO}·${FIN}     ${ACENTO}·${FIN}    ${ACENTO}\\\\${FIN}      ${TENUE}$(t d-lema)${FIN}\n"
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
  p "  %s %s\n" "$(rellena "$(t f-sistema)" 14)"  "$DETALLE_SO"
  p "  %s %s\n" "$(rellena "$(t f-terminal)" 14)" "$INTERPRETE"
  p "  %s %s\n" "$(rellena "$(t f-gestor)" 14)"   "${GESTOR:-$(t f-ninguno)}"
  p "  %s %s\n" "$(rellena "$(t f-java)" 14)"     "$( [ "${jv:-0}" -gt 0 ] 2>/dev/null && echo "$jv" || t f-no-hay )"
  p "  %s %s\n" "$(rellena "$(t f-maven)" 14)"    "$(command -v mvn >/dev/null 2>&1 && mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3 || t f-no-hay)"
  p "  %s %s\n" "$(rellena "$(t f-salida)" 14)"   "$( [ "$VIVO" = si ] && t f-color || t f-plana )"
  if [ "${jv:-0}" -lt "$JAVA_MINIMO" ] 2>/dev/null; then
    p "\n  $(t f-para-java)\n\n      %s\n\n" "$JAVA_MINIMO" "$(orden_java)"
  fi
  exit 0
fi

# ─── 1 · lo que hace falta ──────────────────────────────────────────────────────────────
marca
paso "$(t p-entorno)"

if [ "$SISTEMA" = Windows ]; then
  aviso "$(t a-windows)" "en PowerShell:  irm $BASE/instalar.ps1 | iex"
fi

falta=
for orden in curl tar java mvn; do
  command -v "$orden" >/dev/null 2>&1 || falta="$falta $orden"
done
if [ -n "$falta" ]; then
  mal "$(printf "$(t e-falta)" "$falta")"
  p "\n  ${FUERTE}$(t t-necesita)${FIN}\n" "$JAVA_MINIMO"
  p "  ${TENUE}$(t t-detectado)${FIN}\n\n" "$DETALLE_SO" "${GESTOR:-$(t t-ninguno)}"
  p "      ${FUERTE}%s${FIN}\n\n" "$(orden_java)"
  exit 1
fi

JAVA_V=$(version_java)
if [ "${JAVA_V:-0}" -lt "$JAVA_MINIMO" ] 2>/dev/null; then
  mal "$(printf "$(t e-java-viejo)" "$JAVA_MINIMO" "${JAVA_V:-$(t t-ninguno)}")"
  p "\n  ${TENUE}$(t t-detectado)${FIN}\n\n" "$DETALLE_SO" "${GESTOR:-$(t t-ninguno)}"
  p "      ${FUERTE}%s${FIN}\n\n" "$(orden_java)"
  exit 1
fi
MAVEN_V=$(mvn -v 2>/dev/null | head -1 | cut -d' ' -f1-3)
bien "$(t b-entorno)" "$DETALLE_SO · Java $JAVA_V · $MAVEN_V"

# ─── 2 · qué versión ────────────────────────────────────────────────────────────────────
paso "$(t p-version)"
VERSION=$(curl -fsSL --max-time 20 "$BASE/version" 2>/dev/null) || \
  muere "$(printf "$(t e-sin-red)" "$BASE")"
case "$VERSION" in
  ''|*[!0-9.]*) muere "$(t e-version): '$VERSION'" ;;
esac
bien "$(t b-version)" "Cero $VERSION"

# ─── 3 · bajarlo ────────────────────────────────────────────────────────────────────────
PAQUETE="cero-$VERSION.tar.gz"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/cero.XXXXXX")

paso "$(t p-bajando)"
girando "$TMP/curl.log" \
  curl -fsSL --max-time 300 -o "$TMP/$PAQUETE" "$BASE/estaticos/$PAQUETE" \
  || muere "$(printf "$(t e-descarga)" "$BASE/estaticos/$PAQUETE")" "$TMP/curl.log"
KB=$(( $(wc -c < "$TMP/$PAQUETE") / 1024 ))
bien "$(t b-descargado)" "$PAQUETE · ${KB} KB"

# ─── 4 · comprobar la huella ────────────────────────────────────────────────────────────
paso "$(t p-huella)"
ESPERADA=$(curl -fsSL --max-time 20 "$BASE/estaticos/$PAQUETE.sha256" 2>/dev/null | cut -d' ' -f1) \
  || muere "$(t e-sin-huella)"
if command -v shasum >/dev/null 2>&1; then
  REAL=$(shasum -a 256 "$TMP/$PAQUETE" | cut -d' ' -f1)
elif command -v sha256sum >/dev/null 2>&1; then
  REAL=$(sha256sum "$TMP/$PAQUETE" | cut -d' ' -f1)
else
  REAL=''
fi
if [ -z "$REAL" ]; then
  aviso "$(t b-huella)" "$(t a-sin-sha)"
elif [ "$REAL" != "$ESPERADA" ]; then
  muere "$(t e-huella-mal)
      $(t e-esperada)  $ESPERADA
      $(t e-recibida)  $REAL"
else
  bien "$(t b-huella)" "sha256 $(printf '%s' "$REAL" | cut -c1-16)…"
fi

# ─── 5 · extraer ────────────────────────────────────────────────────────────────────────
paso "$(t p-extrayendo)"
DESTINO="$RAIZ/cero-$VERSION"
mkdir -p "$RAIZ"
rm -rf "$DESTINO"
tar -xzf "$TMP/$PAQUETE" -C "$RAIZ" || muere "$(t e-extraer)"
[ -d "$DESTINO" ] || muere "$(printf "$(t e-contenido)" "cero-$VERSION")"
bien "$(t b-extraido)" "$DESTINO"

# ─── 6 · compilar ───────────────────────────────────────────────────────────────────────
if [ "$PRUEBAS" = si ]; then
  paso "$(t p-compilando-t)"
  girando "$TMP/mvn.log" mvn -B -q -f "$DESTINO/java/pom.xml" install \
    || muere "$(t e-compilar)" "$TMP/mvn.log"
  bien "$(t b-compilado)" "con las pruebas en verde · ${SEGUNDOS:-?} s"
else
  paso "$(t p-compilando)"
  girando "$TMP/mvn.log" mvn -B -q -f "$DESTINO/java/pom.xml" -DskipTests install \
    || muere "$(t e-compilar)" "$TMP/mvn.log"
  bien "$(t b-compilado)" "$(printf "$(t d-modulos)" "${SEGUNDOS:-?}")"
fi

# ─── 7 · dejar la orden a mano ──────────────────────────────────────────────────────────
paso "$(t p-instalando)"
ln -sfn "$DESTINO" "$RAIZ/actual"
mkdir -p "$BIN"
cat > "$BIN/cero" <<GUION
#!/bin/sh
# Generado por el instalador de Cero. Apunta siempre a la versión en uso.
exec "$RAIZ/actual/cero" "\$@"
GUION
chmod +x "$BIN/cero"
bien "$(t b-orden)" "$BIN/cero"

# ─── 8 · comprobar que sirve ────────────────────────────────────────────────────────────
paso "$(t p-comprobando)"
"$BIN/cero" estado >/dev/null 2>&1 || muere "$(t e-no-responde)"
bien "$(t b-comprobado)" "$(t d-responde)"

# ─── resumen ────────────────────────────────────────────────────────────────────────────
p "\n  ${VERDE}${FUERTE}$(t f-instalado)${FIN}\n\n" "$VERSION"
p "  %s %s\n" "$(rellena "$(t f-sistema)" 12)" "$DETALLE_SO"
p "  %s %s\n" "$(rellena "$(t f-java)" 12)" "$JAVA_V · $MAVEN_V"
p "  %s %s\n" "$(rellena "$(t f-carpeta)" 12)" "$DESTINO"
p "  %s %s\n" "$(rellena "$(t f-orden)" 12)" "$BIN/cero"
p "\n"

case ":$PATH:" in
  *":$BIN:"*) ;;
  *)
    p "  ${ACENTO}$(t f-falta-paso)${FIN} — $(t f-path)\n" "$BIN"
    case "$INTERPRETE" in
      zsh)  PERFIL='~/.zshrc' ;;
      fish) PERFIL='~/.config/fish/config.fish' ;;
      *)    PERFIL='~/.bashrc' ;;
    esac
    p "  ${TENUE}$(t f-perfil)${FIN}\n\n" "$PERFIL"
    p "      ${FUERTE}export PATH=\"%s:\$PATH\"${FIN}\n\n" "$(printf '%s' "$BIN" | sed "s|^$HOME|\$HOME|")" ;;
esac

p "  ${TENUE}$(t f-crear)${FIN}\n\n"
p "      ${FUERTE}cero new mi-app${FIN}\n"
p "      ${FUERTE}cd mi-app && mvn -q package && java -Xmx64m -jar target/mi-app.jar${FIN}\n\n"
p "  ${TENUE}$(t f-guia)${FIN}  %s/empezar\n\n" "$BASE"
