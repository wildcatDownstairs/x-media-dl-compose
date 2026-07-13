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

is_wsl() {
  grep -qi microsoft /proc/version 2>/dev/null
}

to_unix_path() {
  local input="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -u "$input"
  elif command -v wslpath >/dev/null 2>&1; then
    wslpath "$input"
  elif [[ "$input" =~ ^([A-Za-z]):\\(.*)$ ]]; then
    local drive_letter="${BASH_REMATCH[1],,}"
    local path_tail="${BASH_REMATCH[2]//\\//}"
    printf '/%s/%s\n' "$drive_letter" "$path_tail"
  else
    printf '%s\n' "$input"
  fi
}

windows_path_exists() {
  local input="$1"

  [[ -e "$(to_unix_path "$input")" ]]
}

resolve_sdk_dir_windows() {
  if [[ -f "${LOCAL_PROPERTIES_PATH}" ]]; then
    local sdk_line
    sdk_line="$(sed -n 's/^sdk\.dir=//p' "${LOCAL_PROPERTIES_PATH}" | head -n 1)"
    if [[ -n "${sdk_line}" ]]; then
      printf '%s\n' "${sdk_line}" | tr -d '\r' | sed 's#\\\\#\\#g; s#\\:#:#g'
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
    if windows_path_exists "${candidate}\\bin\\java.exe"; then
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

if is_wsl && ! cmd.exe /c 'exit 0' >/dev/null 2>&1; then
  fail "当前 WSL 已禁用 Windows interop，无法调用 java.exe/adb.exe。请改在 PowerShell 中运行: .\\Scripts\\build-and-install.ps1 [设备序列号]"
fi

SDK_DIR_WINDOWS="$(resolve_sdk_dir_windows)"
JAVA_HOME_WINDOWS="$(resolve_java_home_windows)"
SDK_DIR="$(to_unix_path "${SDK_DIR_WINDOWS}")"
JAVA_HOME_UNIX="$(to_unix_path "${JAVA_HOME_WINDOWS}")"
ADB_BIN="${SDK_DIR}/platform-tools/adb.exe"
GRADLEW_BIN="${PROJECT_ROOT}/gradlew"
DEVICE_SERIAL="$(pick_device_serial "${ADB_BIN}" "${1:-}")"

[[ -f "${ADB_BIN}" ]] || fail "adb not found: ${ADB_BIN}"

log "Project root: ${PROJECT_ROOT}"
log "JAVA_HOME: ${JAVA_HOME_WINDOWS}"
log "ANDROID_SDK_ROOT: ${SDK_DIR_WINDOWS}"
log "Device: ${DEVICE_SERIAL}"
log "Building debug APK..."

if is_wsl; then
  # WSL 下 Linux 版 gradlew 无法使用 Windows JBR（缺少 bin/java），
  # 改用 cmd.exe 调 gradlew.bat 以 Windows 原生 Java 构建。
  # 通过临时批处理文件执行，避免 WSL 到 cmd.exe 的引号转义问题。
  PROJECT_ROOT_WINDOWS="$(wslpath -w "${PROJECT_ROOT}")"
  BUILD_CMD_FILE="${PROJECT_ROOT}/.build-debug-tmp.cmd"
  trap 'rm -f "${BUILD_CMD_FILE}"' EXIT
  printf '@echo off\r\ncd /d "%s"\r\nset "JAVA_HOME=%s"\r\nset "ANDROID_SDK_ROOT=%s"\r\ncall gradlew.bat :app:assembleDebug\r\n' \
    "${PROJECT_ROOT_WINDOWS}" "${JAVA_HOME_WINDOWS}" "${SDK_DIR_WINDOWS}" > "${BUILD_CMD_FILE}"
  cmd.exe /c "$(wslpath -w "${BUILD_CMD_FILE}")"
else
  [[ -x "${GRADLEW_BIN}" ]] || fail "gradlew is not executable: ${GRADLEW_BIN}"
  export JAVA_HOME="${JAVA_HOME_UNIX}"
  export ANDROID_HOME="${SDK_DIR}"
  export ANDROID_SDK_ROOT="${SDK_DIR}"
  "${GRADLEW_BIN}" -p "${PROJECT_ROOT}" :app:assembleDebug
fi

[[ -f "${APK_PATH}" ]] || fail "APK not found after build: ${APK_PATH}"

log "Installing APK as an in-place update (preserving app data)..."
APK_PATH_FOR_ADB="${APK_PATH}"
if is_wsl; then
  # adb.exe is a Windows program even when launched from WSL, so it needs a Windows path.
  APK_PATH_FOR_ADB="$(wslpath -w "${APK_PATH}")"
fi

"${ADB_BIN}" -s "${DEVICE_SERIAL}" install -r "${APK_PATH_FOR_ADB}"

log "Done."
