#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${PYTHON:-python3}"

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "Python 3 no esta instalado." >&2
  exit 1
fi

"$PYTHON" -m venv "$ROOT_DIR/.venv"
"$ROOT_DIR/.venv/bin/python" -m pip install --upgrade pip
"$ROOT_DIR/.venv/bin/pip" install -r "$ROOT_DIR/requirements-wifi.txt"

echo "Entorno Wi-Fi instalado en $ROOT_DIR/.venv"
echo "Ahora ejecuta: ./run.sh wifi"
