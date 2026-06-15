#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/common.sh"

ADB="$(find_adb || true)"
if [[ -z "$ADB" ]]; then
  echo "ADB not found. Install Android platform-tools or set ADB." >&2
  exit 1
fi

APK="${ROOT_DIR}/dist/PasarFoto.apk"

"$ROOT_DIR/scripts/build-apk.sh"

"$ADB" wait-for-device
"$ADB" install -r "$APK"
"$ADB" shell am start -n dev.pasarfoto.app/.MainActivity

echo "App installed and opened."
echo "Ahora inicia el receptor verificado con:"
echo "  ${ROOT_DIR}/scripts/use-usb.sh"
