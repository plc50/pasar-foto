#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 INPUT_VIDEO [OUTPUT_GIF]" >&2
  exit 1
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is required." >&2
  exit 1
fi

INPUT="$1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${2:-$ROOT_DIR/docs/assets/demo-transfer.gif}"
PALETTE="$(mktemp --suffix=.png)"
trap 'rm -f "$PALETTE"' EXIT

mkdir -p "$(dirname "$OUTPUT")"

FILTER="fps=12,scale=960:-1:flags=lanczos"
ffmpeg -y -i "$INPUT" -vf "$FILTER,palettegen=stats_mode=diff" "$PALETTE"
ffmpeg -y -i "$INPUT" -i "$PALETTE" \
  -lavfi "$FILTER [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
  "$OUTPUT"

echo "Created $OUTPUT"
