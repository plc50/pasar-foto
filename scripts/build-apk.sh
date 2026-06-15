#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/common.sh"

ANDROID_HOME="$(find_android_home || true)"
if [[ -z "$ANDROID_HOME" ]]; then
  echo "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 1
fi

PLATFORM="${ANDROID_PLATFORM:-$(latest_child "$ANDROID_HOME/platforms")}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$(latest_child "$ANDROID_HOME/build-tools")}"

if [[ -z "$PLATFORM" ]] || [[ -z "$BUILD_TOOLS" ]]; then
  echo "Android platform or build-tools not found inside $ANDROID_HOME." >&2
  exit 1
fi

AAPT2="${ANDROID_HOME}/build-tools/${BUILD_TOOLS}/aapt2"
D8="${ANDROID_HOME}/build-tools/${BUILD_TOOLS}/d8"
ZIPALIGN="${ANDROID_HOME}/build-tools/${BUILD_TOOLS}/zipalign"
APKSIGNER="${ANDROID_HOME}/build-tools/${BUILD_TOOLS}/apksigner"
ANDROID_JAR="${ANDROID_HOME}/platforms/${PLATFORM}/android.jar"

APP_DIR="${ROOT_DIR}/app/src/main"
BUILD_DIR="${ROOT_DIR}/build"
OUT_DIR="${ROOT_DIR}/dist"
KEYSTORE="${ANDROID_KEYSTORE:-${ROOT_DIR}/signing/debug.keystore}"
KEY_ALIAS="${ANDROID_KEY_ALIAS:-androiddebugkey}"
KEYSTORE_PASSWORD="${ANDROID_KEYSTORE_PASSWORD:-android}"
KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
UNALIGNED_APK="${BUILD_DIR}/PasarFoto-unsigned.apk"
ALIGNED_APK="${BUILD_DIR}/PasarFoto-aligned.apk"
SIGNED_APK="${OUT_DIR}/PasarFoto.apk"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR"; do
  if [[ ! -e "$tool" ]]; then
    echo "Missing Android SDK file: $tool" >&2
    exit 1
  fi
done

rm -rf "$BUILD_DIR" "$OUT_DIR"
mkdir -p "$BUILD_DIR/compiled" "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$OUT_DIR" "$(dirname "$KEYSTORE")"

"$AAPT2" compile --dir "$APP_DIR/res" -o "$BUILD_DIR/compiled/resources.zip"
"$AAPT2" link \
  -o "$UNALIGNED_APK" \
  -I "$ANDROID_JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/generated" \
  --min-sdk-version 29 \
  --target-sdk-version 36 \
  --version-code 2 \
  --version-name 2.0.0 \
  "$BUILD_DIR/compiled/resources.zip"

mapfile -t JAVA_SOURCES < <(find "$APP_DIR/java" "$BUILD_DIR/generated" -name '*.java' -print)
javac \
  -encoding UTF-8 \
  -Xlint:-options \
  -source 8 \
  -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  "${JAVA_SOURCES[@]}"

mapfile -t CLASS_FILES < <(find "$BUILD_DIR/classes" -name '*.class' -print)
"$D8" \
  --lib "$ANDROID_JAR" \
  --min-api 29 \
  --output "$BUILD_DIR/dex" \
  "${CLASS_FILES[@]}"

cp "$UNALIGNED_APK" "$BUILD_DIR/PasarFoto-with-dex.apk"
(
  cd "$BUILD_DIR/dex"
  zip -q -r "$BUILD_DIR/PasarFoto-with-dex.apk" classes.dex
)

"$ZIPALIGN" -p -f 4 "$BUILD_DIR/PasarFoto-with-dex.apk" "$ALIGNED_APK"

if [[ ! -f "$KEYSTORE" ]]; then
  if [[ -n "${ANDROID_KEYSTORE:-}" ]]; then
    echo "Configured Android keystore does not exist: $KEYSTORE" >&2
    exit 1
  fi
  export PASAR_FOTO_KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
  export PASAR_FOTO_KEY_PASSWORD="$KEY_PASSWORD"
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass:env PASAR_FOTO_KEYSTORE_PASSWORD \
    -keypass:env PASAR_FOTO_KEY_PASSWORD \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" \
    >/dev/null 2>&1
fi

export PASAR_FOTO_KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
export PASAR_FOTO_KEY_PASSWORD="$KEY_PASSWORD"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:PASAR_FOTO_KEYSTORE_PASSWORD \
  --key-pass env:PASAR_FOTO_KEY_PASSWORD \
  --out "$SIGNED_APK" \
  "$ALIGNED_APK"

"$APKSIGNER" verify "$SIGNED_APK"
echo "Built $SIGNED_APK"
