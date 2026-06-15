#!/usr/bin/env bash
set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/common.sh"
MODE="${1:-usb}"

errors=0

ok() {
  printf '  [OK] %s\n' "$1"
}

fail() {
  printf '  [--] %s\n' "$1"
  errors=$((errors + 1))
}

printf 'Pasar Foto system check\n\n'

if [[ "$(uname -s)" == "Linux" ]]; then
  ok "Linux kernel"
else
  fail "This receiver currently supports Linux only"
fi

if command -v bash >/dev/null 2>&1; then
  ok "Bash"
else
  fail "Bash is required"
fi

if command -v python3 >/dev/null 2>&1; then
  ok "Python 3"
else
  fail "Python 3 is required"
fi

if [[ "$MODE" == "usb" ]]; then
  ADB_PATH="$(find_adb || true)"
  if [[ -n "$ADB_PATH" ]]; then
    ok "ADB: $ADB_PATH"
  else
    fail "ADB / Android platform-tools"
  fi
elif [[ "$MODE" == "wifi" ]]; then
  if command -v ip >/dev/null 2>&1; then
    ok "iproute2"
  else
    fail "iproute2 is required"
  fi
  if command -v qrencode >/dev/null 2>&1; then
    ok "qrencode"
  else
    fail "qrencode is required"
  fi
  WIFI_PYTHON="$ROOT_DIR/.venv/bin/python"
  if [[ ! -x "$WIFI_PYTHON" ]]; then
    WIFI_PYTHON="$(command -v python3 || true)"
  fi
  if [[ -n "$WIFI_PYTHON" ]] \
    && "$WIFI_PYTHON" -c "import cryptography" >/dev/null 2>&1; then
    ok "Python cryptography"
  else
    fail "Run ./scripts/install-wifi.sh"
  fi
else
  fail "Unknown mode '$MODE'; use usb or wifi"
fi

if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
  if command -v wl-copy >/dev/null 2>&1; then
    ok "Wayland clipboard: wl-copy"
  else
    fail "Wayland detected but wl-copy is missing"
  fi
elif [[ -n "${DISPLAY:-}" ]]; then
  if command -v xclip >/dev/null 2>&1; then
    ok "X11 clipboard: xclip"
  else
    fail "X11 detected but xclip is missing"
  fi
elif command -v wl-copy >/dev/null 2>&1; then
  ok "Clipboard candidate: wl-copy"
elif command -v xclip >/dev/null 2>&1; then
  ok "Clipboard candidate: xclip"
else
  fail "Install wl-clipboard or xclip"
fi

if [[ "$errors" -eq 0 ]]; then
  printf '\nCompatible environment detected.\n'
  exit 0
fi

printf '\nFound %s missing requirement(s).\n' "$errors"
exit 1
