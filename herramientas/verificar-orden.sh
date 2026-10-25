#!/bin/sh
cd "$(dirname "$0")/.." || exit 1
fallo=0

salida=$([ -f .superpowers/autores-ajenos ] && git grep -nI -f .superpowers/autores-ajenos --)
if [ -n "$salida" ]; then
  echo "autores ajenos:"
  echo "$salida"
  fallo=1
fi

salida=$(git grep -nIi 'jxmvc' -- ':!benchmarks/results/*.csv')
if [ -n "$salida" ]; then
  echo "referencias a jxmvc:"
  echo "$salida"
  fallo=1
fi

if ! ./cero --version 2>/dev/null | grep -q 'Cero · por Andre Solorzano'; then
  echo "./cero --version no imprime el sello"
  fallo=1
fi

exit $fallo
