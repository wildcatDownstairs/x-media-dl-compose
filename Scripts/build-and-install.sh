#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCAL_PROPERTIES_PATH="${PROJECT_ROOT}/local.properties"
APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk"

log() {
  printf '[xmediadl] %s\n' "$1"
}

fail() {
  printf '[xmediadl] Error: %s\n' "$1" >&2
  exit 1
}

to_unix_path() {
  local input="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u "$input"
  else
    printf '%s\n' "$input"
  fi
}

resolve_sdk_dir_windows() {
  if [[ -f "${LOCAL_PROPERTIES_PATH}" ]]; then
    local sdk_line
    sdk_line="$(sed -n 's/^sdk\.dir=//p' "${LOCAL_PROPERTIES_PATH}" | head -n 1)"
    if [[ -n "${sdk_line}" ]]; then
      printf '%s\n' "${sdk_line}" | sed 's#\\\\#\\#g; s#\\:#:#g'
      return 0
    fi
  fi

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s\n' "${ANDROID_SDK_ROOT}"
    return 0
  fi

  if [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "${ANDROID_HOME}"
    return 0
  fi

  fail "Android SDK path not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or local.properties."
}

resolve_java_home_windows() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    printf '%s\n' "${JAVA_HOME}"
    return 0
  fi

  local candidates=(
    'C:\Program Files\Android\Android Studio1\jbr'
    'C:\Program Files\Android\Android Studio\jbr'
    'C:\Program Files\Android\Android Studio Preview\jbr'
  )

  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -d "$(to_unix_path "${candidate}")" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  fail "JAVA_HOME not set and Android Studio JBR was not found in common locations."
}

pick_device_serial() {
  local adb_bin="$1"
  local requested_serial="${2:-}"

  if [[ -n "${requested_serial}" ]]; then
    printf '%s\n' "${requested_serial}"
    return 0
  fi

  mapfile -t devices < <("${adb_bin}" devices | awk 'NR > 1 && $2 == "device" { print $1 }')

  if [[ "${#devices[@]}" -eq 0 ]]; then
    fail "No connected Android device found."
  fi

  if [[ "${#devices[@]}" -gt 1 ]]; then
    printf '[xmediadl] Connected devices:\n' >&2
    printf '  - %s\n' "${devices[@]}" >&2
    fail "Multiple devices found. Run: bash Scripts/build-and-install.sh <device-serial>"
  fi

  printf '%s\n' "${devices[0]}"
}

SDK_DIR_WINDOWS="$(resolve_sdk_dir_windows)"
JAVA_HOME_WINDOWS="$(resolve_java_home_windows)"
SDK_DIR="$(to_unix_path "${SDK_DIR_WINDOWS}")"
JAVA_HOME_UNIX="$(to_unix_path "${JAVA_HOME_WINDOWS}")"
ADB_BIN="${SDK_DIR}/platform-tools/adb.exe"
GRADLEW_BIN="${PROJECT_ROOT}/gradlew"
DEVICE_SERIAL="$(pick_device_serial "${ADB_BIN}" "${1:-}")"

[[ -x "${GRADLEW_BIN}" ]] || fail "gradlew is not executable: ${GRADLEW_BIN}"
[[ -f "${ADB_BIN}" ]] || fail "adb not found: ${ADB_BIN}"

export JAVA_HOME="${JAVA_HOME_UNIX}"
export ANDROID_HOME="${SDK_DIR}"
export ANDROID_SDK_ROOT="${SDK_DIR}"

log "Project root: ${PROJECT_ROOT}"
log "JAVA_HOME: ${JAVA_HOME}"
log "ANDROID_SDK_ROOT: ${ANDROID_SDK_ROOT}"
log "Device: ${DEVICE_SERIAL}"
log "Building debug APK..."

"${GRADLEW_BIN}" -p "${PROJECT_ROOT}" :app:assembleDebug

[[ -f "${APK_PATH}" ]] || fail "APK not found after build: ${APK_PATH}"

log "Installing APK..."
"${ADB_BIN}" -s "${DEVICE_SERIAL}" install -r "${APK_PATH}"

log "Done."
