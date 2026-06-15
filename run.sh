#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODE="${1:-usb}"
case "$MODE" in
  usb|--usb)
    exec "$ROOT_DIR/scripts/use-usb.sh"
    ;;
  wifi|wi-fi|--wifi|--wi-fi)
    exec "$ROOT_DIR/scripts/use-wifi.sh"
    ;;
  *)
    echo "Usage: $0 [usb|wifi]" >&2
    exit 2
    ;;
esac
