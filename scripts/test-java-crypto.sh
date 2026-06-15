#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

javac \
  -encoding UTF-8 \
  -d "$BUILD_DIR" \
  "$ROOT_DIR/app/src/main/java/dev/pasarfoto/app/CryptoUtils.java" \
  "$ROOT_DIR/tests/java/dev/pasarfoto/app/CryptoVectorTest.java"

java -cp "$BUILD_DIR" dev.pasarfoto.app.CryptoVectorTest
