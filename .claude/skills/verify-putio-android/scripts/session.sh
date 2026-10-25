#!/usr/bin/env bash
# Devbox side of verify-putio-android: one emulator and one app install per run.
# Run it in the devbox copy (through devbox.sh run).
#
#   session.sh up      <run>   create AVD <run> from putio-phone's image, boot it headless,
#                              write .evidence/<run>/run.env, print the serial
#   session.sh install <run>   install the built production debug APK on the run's serial (adb install -r)
#   session.sh down    <run>   force-stop the app, stop the run's serial, delete AVD <run>;
#                              .evidence/<run>/ stays
#
# Only the serial whose `emu avd name` is <run> is ever stopped; other emulators
# and the attached phones are never touched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
PACKAGE="${PUTIO_VERIFY_PACKAGE:-io.put.putio.mobile.debug}"
APK="${ROOT}/mobile/build/outputs/apk/production/debug/mobile-production-debug.apk"
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
ADB="${ANDROID_HOME}/platform-tools/adb"

die() { echo "session.sh: $*" >&2; exit 1; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }

cmd="${1:-}"; run="${2:-}"
[[ -n "${cmd}" && "${run}" =~ ^[A-Za-z0-9._-]+$ ]] || usage
DIR="${ROOT}/.evidence/${run}"
ENV_FILE="${DIR}/run.env"

env_value() { [[ -f "${ENV_FILE}" ]] && sed -n "s/^$1=//p" "${ENV_FILE}" | tail -1; }

# Succeeds when <serial> is a running emulator of AVD <run>.
owns_serial() {
  local serial="$1" name
  [[ "$("${ADB}" -s "${serial}" get-state 2>/dev/null)" == "device" ]] || return 1
  name="$("${ADB}" -s "${serial}" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  [[ "${name}" == "${run}" ]]
}

case "${cmd}" in
  up)
    mkdir -p "${DIR}"
    if [[ "$(env_value STATE)" == "up" ]] && owns_serial "$(env_value SERIAL)"; then
      env_value SERIAL; exit 0
    fi
    "${ROOT}/scripts/emulator.sh" create phone --name "${run}" >&2
    serial="$("${ROOT}/scripts/emulator.sh" boot phone --headless --name "${run}")" || die "boot failed; see .evidence/logs/"
    owns_serial "${serial}" || die "${serial} is not AVD ${run}; leaving it alone"
    {
      echo "RUN=${run}"
      echo "AVD=${run}"
      echo "SERIAL=${serial}"
      echo "PACKAGE=${PACKAGE}"
      echo "STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "STATE=up"
    } > "${ENV_FILE}"
    echo "${serial}"
    ;;
  install)
    serial="$(env_value SERIAL)"
    owns_serial "${serial}" || die "no running emulator for run ${run}; run session.sh up ${run}"
    [[ -f "${APK}" ]] || die "missing ${APK}; run ./gradlew --max-workers=6 :mobile:assembleProductionDebug"
    "${ADB}" -s "${serial}" install -r "${APK}" >&2
    echo "installed $(shasum -a 256 "${APK}" | cut -c1-12) on ${serial}"
    ;;
  down)
    serial="$(env_value SERIAL || true)"
    if [[ -n "${serial}" ]] && owns_serial "${serial}"; then
      "${ADB}" -s "${serial}" shell am force-stop "${PACKAGE}" || true
      "${ROOT}/scripts/emulator.sh" stop "${serial}" >&2
    else
      echo "no running emulator for run ${run}" >&2
    fi
    "${ROOT}/scripts/emulator.sh" delete phone --name "${run}" >&2
    [[ -f "${ENV_FILE}" ]] && sed -i '' 's/^STATE=up$/STATE=down/' "${ENV_FILE}"
    "${ADB}" devices | grep -q "^${serial:-none}[[:space:]]" && die "${serial} still listed by adb"
    echo "down ${run}; evidence kept in .evidence/${run}/"
    ;;
  *) usage ;;
esac
