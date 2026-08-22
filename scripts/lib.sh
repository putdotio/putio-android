# shellcheck shell=bash
# Shared helpers for the harness scripts. Source, don't execute.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# SDK root resolution order: ANDROID_HOME, ANDROID_SDK_ROOT, local.properties
# sdk.dir, then known install locations.
resolve_sdk_root() {
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
    echo "${ANDROID_HOME}"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}" ]]; then
    echo "${ANDROID_SDK_ROOT}"
    return
  fi
  local props="${REPO_ROOT}/local.properties"
  if [[ -f "${props}" ]]; then
    local from_props
    from_props="$(sed -n 's/^sdk\.dir=//p' "${props}" | head -1)"
    if [[ -n "${from_props}" && -d "${from_props}" ]]; then
      echo "${from_props}"
      return
    fi
  fi
  local candidate
  for candidate in /opt/homebrew/share/android-commandlinetools "${HOME}/Library/Android/sdk" "${HOME}/Android/Sdk"; do
    if [[ -d "${candidate}" ]]; then
      echo "${candidate}"
      return
    fi
  done
  return 1
}

# shellcheck disable=SC2034  # consumed by the sourcing scripts
require_sdk_root() {
  SDK_ROOT="$(resolve_sdk_root)" || die "no Android SDK root found; run scripts/bootstrap.sh"
  ADB="${SDK_ROOT}/platform-tools/adb"
  EMULATOR_BIN="${SDK_ROOT}/emulator/emulator"
  AVDMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/avdmanager"
  SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
  [[ -x "${ADB}" ]] || die "adb missing at ${ADB}; run scripts/bootstrap.sh"
}

# Reusable AVD names and images. Phone and TV share API 36 so one platform
# level serves both; TV system images are not published for 37 yet.
PHONE_AVD="putio-phone"
TV_AVD="putio-tv"

sdk_arch() {
  case "$(uname -m)" in
    arm64|aarch64) echo "arm64-v8a" ;;
    x86_64) echo "x86_64" ;;
    *) die "unsupported host arch: $(uname -m)" ;;
  esac
}

phone_image() { echo "system-images;android-36;google_apis;$(sdk_arch)"; }
tv_image() { echo "system-images;android-36;android-tv;$(sdk_arch)"; }

# profile -> AVD name / image / avdmanager device id
avd_name_for() {
  case "$1" in
    phone) echo "${PHONE_AVD}" ;;
    tv) echo "${TV_AVD}" ;;
    *) die "unknown emulator profile '$1' (expected phone|tv)" ;;
  esac
}
image_for() {
  case "$1" in
    phone) phone_image ;;
    tv) tv_image ;;
  esac
}
device_for() {
  case "$1" in
    phone) echo "pixel_7" ;;
    tv) echo "tv_1080p" ;;
  esac
}

avd_exists() {
  "${AVDMANAGER}" list avd -c 2>/dev/null | grep -qx "$1"
}

# Serial of a running emulator whose AVD name matches $1, empty if none.
serial_for_avd() {
  local wanted="$1" serial avd
  while read -r serial; do
    avd="$("${ADB}" -s "${serial}" emu avd name 2>/dev/null | head -1 | tr -d '\r')" || continue
    if [[ "${avd}" == "${wanted}" ]]; then
      echo "${serial}"
      return 0
    fi
  done < <("${ADB}" devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1}')
  return 1
}

# First free even console port in the emulator range.
free_emulator_port() {
  local port
  for port in $(seq 5554 2 5584); do
    if ! lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "${port}"
      return 0
    fi
  done
  die "no free emulator console port between 5554 and 5584"
}

wait_serial_gone() {
  local serial="$1" deadline=$(( $(date +%s) + ${2:-30} ))
  while (( $(date +%s) < deadline )); do
    if ! "${ADB}" devices | awk '{print $1}' | grep -qx "${serial}"; then
      return 0
    fi
    sleep 1
  done
  return 1
}
