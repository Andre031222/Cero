#!/bin/sh
cd "$(dirname "$0")/.." || exit 1
fallo=0

salida=$([ -f .superpowers/autores-ajenos ] && git grep -nI -f .superpowers/autores-ajenos -- ':!herramientas/verificar-orden.sh')
if [ -n "$salida" ]; then
  echo "autores ajenos:"
  echo "$salida"
  fallo=1
fi

salida=$(git grep -nIi 'jxmvc' -- ':!benchmarks/results' ':!papers/LEEME.md' ':!docs/papers.md' ':!herramientas/verificar-orden.sh')
if [ -n "$salida" ]; then
  echo "referencias a jxmvc:"
  echo "$salida"
  fallo=1
fi

salida=$(git grep -nE 'cero[.-]view|ViewRenderer|\bLive\b' -- java cero cero.ps1 .github)
if [ -n "$salida" ]; then
  echo "restos de las vistas:"
  echo "$salida"
  fallo=1
fi

if ! ./cero --version 2>/dev/null | grep -q 'Cero · por Andre Solorzano'; then
  echo "./cero --version no imprime el sello"
  fallo=1
fi

if ! git ls-files -z -- java rust c++ herramientas | xargs -0 python3 herramientas/comentarios.py --verificar; then
  echo "código con comentarios"
  fallo=1
fi

if grep -qiE 'bare-metal|sin virtualizar' README.md && grep -qi 'docker desktop' benchmarks/results/ENV.md; then
  echo "el README dice bare-metal o sin virtualizar, y ENV.md describe Docker Desktop"
  fallo=1
fi

if ! grep -qx 'Soft_Cero-Web/' .gitignore; then
  echo "Soft_Cero-Web/ no figura en .gitignore"
  fallo=1
fi

salida=$(find . -name .DS_Store -not -path './.git/*' -not -path '*/target/*' -not -path './Soft_Cero-Web/*')
if [ -n "$salida" ]; then
  echo "archivos .DS_Store:"
  echo "$salida"
  fallo=1
fi

exit $fallo
