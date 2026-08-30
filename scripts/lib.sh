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
  local candidate brew_prefix=""
  # The Homebrew cask lands under the active prefix: /opt/homebrew on Apple
  # Silicon, /usr/local on Intel macOS, /home/linuxbrew/.linuxbrew on Linux.
  if command -v brew >/dev/null 2>&1; then
    brew_prefix="$(brew --prefix 2>/dev/null || true)"
  fi
  for candidate in \
    ${brew_prefix:+"${brew_prefix}/share/android-commandlinetools"} \
    /opt/homebrew/share/android-commandlinetools \
    /usr/local/share/android-commandlinetools \
    "${HOME}/Library/Android/sdk" \
    "${HOME}/Android/Sdk"; do
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

# Reusable AVD names and images. The phone uses API 37 because its Google Play
# image includes a Chrome build with AndroidX Auth Tab support. Android TV
# system images are not published for API 37 yet, so TV remains on API 36.
PHONE_AVD="putio-phone"
TV_AVD="putio-tv"
PHONE_API_LEVEL="37"
CHROME_PACKAGE="com.android.chrome"
AUTH_TAB_SERVICE_ACTION="android.support.customtabs.action.CustomTabsService"
AUTH_TAB_SERVICE_CATEGORY="androidx.browser.auth.category.AuthTab"

sdk_arch() {
  case "$(uname -m)" in
    arm64|aarch64) echo "arm64-v8a" ;;
    x86_64) echo "x86_64" ;;
    *) die "unsupported host arch: $(uname -m)" ;;
  esac
}

phone_image() { echo "system-images;android-37.0;google_apis_playstore;$(sdk_arch)"; }
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

avd_delete_command() {
  local profile="$1" name="$2" command
  command="scripts/emulator.sh delete ${profile}"
  [[ "${name}" == "$(avd_name_for "${profile}")" ]] || command+=" --name ${name}"
  echo "${command}"
}

avd_stop_command() {
  local profile="$1" name="$2" command
  command="scripts/emulator.sh stop ${profile}"
  [[ "${name}" == "$(avd_name_for "${profile}")" ]] || command+=" --name ${name}"
  echo "${command}"
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

validate_avd_name() {
  [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid AVD name '$1'"
}

avd_path_for() {
  local wanted="$1"
  "${AVDMANAGER}" list avd 2>/dev/null | awk -v wanted="${wanted}" '
    /^[[:space:]]*Name:/ {
      name = $0
      sub(/^[[:space:]]*Name:[[:space:]]*/, "", name)
      next
    }
    name == wanted && /^[[:space:]]*Path:/ {
      path = $0
      sub(/^[[:space:]]*Path:[[:space:]]*/, "", path)
      print path
      exit
    }
  '
}

avd_registered() {
  avd_exists "$1" || [[ -n "$(avd_path_for "$1")" ]]
}

avd_image_for_name() {
  local name="$1" path image
  path="$(avd_path_for "${name}")"
  [[ -n "${path}" && -f "${path}/config.ini" ]] || return 1
  image="$(awk '
    /^image\.sysdir\.1=/ {
      count++
      value = $0
      sub(/^image\.sysdir\.1=/, "", value)
    }
    END {
      if (count != 1 || value == "") exit 1
      print value
    }
  ' "${path}/config.ini")" || return 1
  [[ -n "${image}" ]] || return 1
  sed -E 's#/$##; s#/#;#g' <<<"${image}"
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

# Print a script's leading comment header as usage text (skips the shebang,
# stops at the first non-comment line).
print_usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$1"
}

# Idempotent prep for every serial the harness touches, on boot AND reuse.
# Cold headless boots regularly ANR com.android.systemui and the dialog then
# sits over every capture; app ANRs are detected from logcat instead.
prepare_device() {
  local serial="$1" profile="$2" avd_name="${3:-$(avd_name_for "$2")}" delete_command recovery
  "${ADB}" -s "${serial}" shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || \
    log "WARNING: could not set hide_error_dialogs on ${serial}"

  [[ "${profile}" == "phone" ]] || return 0

  delete_command="$(avd_delete_command "${profile}" "${avd_name}")"
  recovery="explicitly run scripts/emulator.sh stop ${serial}, then ${delete_command}, then scripts/bootstrap.sh"
  local api_level role_holders auth_tab_services chrome_version
  api_level="$("${ADB}" -s "${serial}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')" || \
    die "could not read the API level from ${serial}; ${recovery}"
  [[ "${api_level}" == "${PHONE_API_LEVEL}" ]] || \
    die "phone emulator ${serial} is API ${api_level:-unknown}; expected API ${PHONE_API_LEVEL}; ${recovery}"

  "${ADB}" -s "${serial}" shell pm path --user 0 "${CHROME_PACKAGE}" >/dev/null 2>&1 || \
    die "Chrome (${CHROME_PACKAGE}) is missing on ${serial}; ${recovery}"
  "${ADB}" -s "${serial}" shell cmd role add-role-holder --user 0 \
    android.app.role.BROWSER "${CHROME_PACKAGE}" >/dev/null 2>&1 || \
    die "could not select Chrome as the browser on ${serial}; ${recovery}"
  role_holders="$("${ADB}" -s "${serial}" shell cmd role get-role-holders --user 0 \
    android.app.role.BROWSER 2>/dev/null | tr -d '\r')" || \
    die "could not read browser role holders on ${serial}; ${recovery}"
  [[ "${role_holders}" == "${CHROME_PACKAGE}" ]] || \
    die "Chrome is not the sole browser role holder on ${serial}; ${recovery}"

  auth_tab_services="$("${ADB}" -s "${serial}" shell cmd package query-services \
    -a "${AUTH_TAB_SERVICE_ACTION}" -c "${AUTH_TAB_SERVICE_CATEGORY}" 2>/dev/null)" || \
    die "could not query Auth Tab support on ${serial}; ${recovery}"
  awk -v package="${CHROME_PACKAGE}" \
    '$1 == "packageName=" package { found=1 } END { exit !found }' <<<"${auth_tab_services}" || \
    die "Chrome on ${serial} does not expose AndroidX Auth Tab support; ${recovery}"

  chrome_version="$("${ADB}" -s "${serial}" shell dumpsys package "${CHROME_PACKAGE}" 2>/dev/null | \
    awk '/^[[:space:]]*versionName=/ && !found { sub(/^[[:space:]]*versionName=/, ""); print; found=1 }' | \
    tr -d '\r')" || chrome_version=""
  log "phone runtime ready on ${serial}: API ${api_level}, Chrome ${chrome_version:-unknown}, AndroidX Auth Tab"
}

wait_serial_gone() {
  local serial="$1" deadline=$(( $(date +%s) + ${2:-30} )) devices
  while (( $(date +%s) < deadline )); do
    # A failed adb query is not proof the serial is gone; retry instead of
    # letting pipefail turn the error into "confirmed gone".
    if devices="$("${ADB}" devices 2>/dev/null)"; then
      if ! grep -qx "${serial}" <<<"$(awk '{print $1}' <<<"${devices}")"; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}
