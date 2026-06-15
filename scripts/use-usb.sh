#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/common.sh"

ADB="$(find_adb || true)"
PORT=48765
HEALTH_URL="http://127.0.0.1:${PORT}/health"
LAUNCHER_PID_FILE="${TMPDIR:-/tmp}/pasar-foto-usb.pid"
RECEIVER_PID_FILE="${TMPDIR:-/tmp}/pasar-foto-receiver.pid"
RECEIVER_PID=""

if [[ -z "$ADB" ]]; then
  echo "No se encuentra ADB. Instala Android platform-tools o define ADB." >&2
  exit 1
fi

if ! command -v wl-copy >/dev/null 2>&1 \
  && ! command -v xclip >/dev/null 2>&1; then
  echo "Falta un backend de portapapeles: wl-copy o xclip." >&2
  exit 1
fi

cleanup() {
  if [[ -n "$RECEIVER_PID" ]] && kill -0 "$RECEIVER_PID" 2>/dev/null; then
    kill "$RECEIVER_PID" 2>/dev/null || true
    wait "$RECEIVER_PID" 2>/dev/null || true
  fi
  rm -f "$RECEIVER_PID_FILE"
  if [[ -f "$LAUNCHER_PID_FILE" ]] && [[ "$(<"$LAUNCHER_PID_FILE")" == "$$" ]]; then
    rm -f "$LAUNCHER_PID_FILE"
  fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

pid_matches() {
  local pid="$1"
  local expected="$2"
  local command_line

  [[ -r "/proc/${pid}/cmdline" ]] || return 1
  command_line="$(tr '\0' ' ' <"/proc/${pid}/cmdline")"
  [[ "$command_line" == *"$expected"* ]]
}

stop_pid() {
  local pid="$1"
  local label="$2"

  if ! kill -0 "$pid" 2>/dev/null; then
    return
  fi

  echo "Cerrando ${label} anterior (PID ${pid})..."
  kill "$pid" 2>/dev/null || true
  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return
    fi
    sleep 0.1
  done

  echo "El proceso ${pid} no respondio; forzando su cierre." >&2
  kill -KILL "$pid" 2>/dev/null || true
}

stop_previous_processes() {
  local old_pid

  if [[ -f "$LAUNCHER_PID_FILE" ]]; then
    old_pid="$(<"$LAUNCHER_PID_FILE")"
    if [[ "$old_pid" =~ ^[0-9]+$ ]] \
      && [[ "$old_pid" != "$$" ]] \
      && pid_matches "$old_pid" "use-usb.sh"; then
      stop_pid "$old_pid" "script USB"
    fi
  fi

  if [[ -f "$RECEIVER_PID_FILE" ]]; then
    old_pid="$(<"$RECEIVER_PID_FILE")"
    if [[ "$old_pid" =~ ^[0-9]+$ ]] \
      && pid_matches "$old_pid" "${ROOT_DIR}/scripts/receiver.py"; then
      stop_pid "$old_pid" "receptor"
    fi
  fi

  while read -r old_pid; do
    if [[ -n "$old_pid" ]] && [[ "$old_pid" != "$$" ]]; then
      stop_pid "$old_pid" "receptor huerfano"
    fi
  done < <(pgrep -f -- "${ROOT_DIR}/scripts/receiver.py" 2>/dev/null || true)
}

receiver_is_healthy() {
  python3 - "$HEALTH_URL" <<'PY'
import json
import sys
import urllib.request

try:
    with urllib.request.urlopen(sys.argv[1], timeout=1) as response:
        body = json.load(response)
    healthy = (
        response.status == 200
        and body.get("service") == "pasar-foto-receiver"
        and body.get("status") == "ok"
    )
except Exception:
    healthy = False

raise SystemExit(0 if healthy else 1)
PY
}

start_receiver() {
  local exit_code=0

  python3 -u "$ROOT_DIR/scripts/receiver.py" &
  RECEIVER_PID="$!"
  printf '%s\n' "$RECEIVER_PID" >"$RECEIVER_PID_FILE"

  for _ in {1..30}; do
    if receiver_is_healthy; then
      return
    fi
    if ! kill -0 "$RECEIVER_PID" 2>/dev/null; then
      wait "$RECEIVER_PID" || exit_code="$?"
      RECEIVER_PID=""
      echo "El receptor no pudo arrancar (codigo ${exit_code})." >&2
      echo "Es posible que el puerto ${PORT} este ocupado por otro programa." >&2
      exit 1
    fi
    sleep 0.1
  done

  echo "El receptor arranco, pero no responde correctamente en ${HEALTH_URL}." >&2
  exit 1
}

reverse_is_active() {
  "$ADB" reverse --list 2>/dev/null | grep -q "tcp:${PORT} tcp:${PORT}"
}

ensure_reverse() {
  if ! reverse_is_active; then
    echo "Restaurando tunel USB tcp:${PORT}..."
    "$ADB" reverse "tcp:${PORT}" "tcp:${PORT}"
  fi
}

ADB_DEVICES="$("$ADB" devices 2>&1)"
if grep -q "no permissions" <<<"$ADB_DEVICES"; then
  echo "ADB ve el movil, pero no tiene permisos USB. Reiniciando ADB..." >&2
  "$ADB" kill-server >/dev/null 2>&1 || true
  "$ADB" start-server >/dev/null
  ADB_DEVICES="$("$ADB" devices 2>&1)"
fi

if grep -q "no permissions" <<<"$ADB_DEVICES"; then
  echo "ADB sigue sin permisos para acceder al movil." >&2
  echo "En Arch instala las reglas con: sudo pacman -S android-udev" >&2
  echo "Despues desconecta y conecta el cable USB y ejecuta este script otra vez." >&2
  exit 1
fi

if grep -q $'\tunauthorized' <<<"$ADB_DEVICES"; then
  echo "El movil no ha autorizado este PC. Desbloquealo y acepta la depuracion USB." >&2
  exit 1
fi

DEVICE_COUNT="$(grep -c $'\tdevice\\b' <<<"$ADB_DEVICES" || true)"
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "ADB no detecta ningun movil conectado y autorizado." >&2
  echo "Desbloquea el movil, activa Depuracion USB, reconecta el cable y acepta este PC." >&2
  exit 1
fi
if [[ "$DEVICE_COUNT" -gt 1 ]]; then
  echo "Hay varios dispositivos ADB conectados. Deja solo el movil que quieres usar." >&2
  exit 1
fi

stop_previous_processes
printf '%s\n' "$$" >"$LAUNCHER_PID_FILE"

start_receiver
ensure_reverse

if ! receiver_is_healthy || ! reverse_is_active; then
  echo "El diagnostico final del receptor o del tunel USB ha fallado." >&2
  exit 1
fi

echo "Receptor verificado y tunel USB activo. Pulsa Ctrl+C para cerrarlo."
echo "La app comprobara este mismo diagnostico antes de permitir enviar fotos."

while true; do
  if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "Movil desconectado de ADB. Cerrando receptor."
    exit 0
  fi

  if ! kill -0 "$RECEIVER_PID" 2>/dev/null || ! receiver_is_healthy; then
    echo "El receptor ha dejado de responder. Reiniciandolo..." >&2
    if [[ -n "$RECEIVER_PID" ]]; then
      stop_pid "$RECEIVER_PID" "receptor averiado"
    fi
    start_receiver
  fi

  ensure_reverse
  sleep 2
done
