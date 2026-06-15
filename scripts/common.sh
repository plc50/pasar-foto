#!/usr/bin/env bash

find_adb() {
  local candidate

  if [[ -n "${ADB:-}" ]] && [[ -x "$ADB" ]]; then
    printf '%s\n' "$ADB"
    return 0
  fi

  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi

  for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "${HOME}/Android/Sdk/platform-tools/adb"; do
    if [[ "$candidate" != "/platform-tools/adb" ]] && [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

find_android_home() {
  local candidate

  for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "${HOME}/Android/Sdk"; do
    if [[ -n "$candidate" ]] && [[ -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

latest_child() {
  local parent="$1"
  find "$parent" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' \
    | sort -V \
    | tail -n 1
}
