#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$DIR"

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn no esta instalado o no esta en PATH"
  exit 1
fi

mvn clean package

echo "Jar generado en: $DIR/target/jxmvc-core-3.3.0.jar"
