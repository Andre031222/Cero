#!/usr/bin/env sh
# Rehace portada.png: una captura del sitio en producción, que es la que envejecía sin que
# nadie se diera cuenta —la anterior seguía anunciando la 0.5.0 y una pestaña «Migrar» que
# ya no existe—. Sin dependencias: Chrome trae el modo sin ventana.
#
#     sh docs/imagenes/portada.sh
set -eu

AQUI=$(cd "$(dirname "$0")" && pwd)
SITIO=${CERO_SITIO:-https://cero.ginit.dev}
CHROME=${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}

[ -x "$CHROME" ] || { echo "no encuentro Chrome; dime dónde está:  CHROME=/ruta $0" >&2; exit 1; }

# x2 y luego se reduce: el texto sale nítido en pantallas normales y en retina.
"$CHROME" --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
          --window-size=1280,730 --virtual-time-budget=9000 \
          --screenshot="$AQUI/portada@2x.png" "$SITIO" 2>/dev/null

python3 - "$AQUI" <<'PY'
import sys, pathlib
from PIL import Image
aqui = pathlib.Path(sys.argv[1])
doble = aqui / "portada@2x.png"
Image.open(doble).resize((1280, 730), Image.LANCZOS).save(aqui / "portada.png")
doble.unlink()
print(f"{aqui / 'portada.png'}  {(aqui / 'portada.png').stat().st_size / 1024:.0f} KB")
PY
