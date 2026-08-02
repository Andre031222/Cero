#!/usr/bin/env bash
# RSS (memoria residente) de un proceso en estado estacionario, en MB.
# Ejecutar TRAS el warmup de carga. uso: measure-memory.sh <pid>
set -euo pipefail
PID="${1:?pid requerido}"
if [ -r "/proc/$PID/status" ]; then
  kb=$(awk '/VmRSS/{print $2}' "/proc/$PID/status")
else
  kb=$(ps -o rss= -p "$PID" | tr -d ' ')   # macOS/BSD
fi
awk -v kb="$kb" 'BEGIN{ printf "rss_mb=%.1f\n", kb/1024 }'
