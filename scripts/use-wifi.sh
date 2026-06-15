#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_OVERRIDE="${PASAR_FOTO_WIFI_HOST:-}"
WIFI_PORT="${PASAR_FOTO_WIFI_PORT:-48766}"
PYTHON="$ROOT_DIR/.venv/bin/python"

if [[ ! -x "$PYTHON" ]]; then
  PYTHON="$(command -v python3 || true)"
fi

if ! command -v ip >/dev/null 2>&1; then
  echo "Falta el comando 'ip' (paquete iproute2)." >&2
  exit 1
fi
if ! command -v qrencode >/dev/null 2>&1; then
  echo "Falta qrencode. Instala el paquete 'qrencode'." >&2
  exit 1
fi
if [[ -z "$PYTHON" ]] || ! "$PYTHON" -c "import cryptography" >/dev/null 2>&1; then
  echo "Falta la dependencia Python 'cryptography'." >&2
  echo "Ejecuta: ./scripts/install-wifi.sh" >&2
  exit 1
fi
if ! command -v wl-copy >/dev/null 2>&1 \
  && ! command -v xclip >/dev/null 2>&1; then
  echo "Falta wl-copy (Wayland) o xclip (X11)." >&2
  exit 1
fi

NETWORK_ARGS=()
if [[ -n "$HOST_OVERRIDE" ]]; then
  NETWORK_ARGS+=(--host "$HOST_OVERRIDE")
fi

read -r WIFI_HOST WIFI_NETWORK WIFI_INTERFACE < <(
  "$PYTHON" "$ROOT_DIR/scripts/network_info.py" "${NETWORK_ARGS[@]}"
)

echo "Interfaz seleccionada: $WIFI_INTERFACE"
echo "El receptor se vinculara solo a $WIFI_HOST y aceptara $WIFI_NETWORK."
echo "El movil y el PC deben poder comunicarse dentro de esa red."

exec "$PYTHON" -u "$ROOT_DIR/scripts/receiver_wifi.py" \
  --host "$WIFI_HOST" \
  --network "$WIFI_NETWORK" \
  --port "$WIFI_PORT"
