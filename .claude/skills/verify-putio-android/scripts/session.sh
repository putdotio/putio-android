#!/usr/bin/env bash
# Devbox side of verify-putio-android: one emulator and one app install per run.
# Run it in the devbox copy (through devbox.sh run).
#
#   session.sh up      <run> --snapshot   boot a read-only putio-phone instance from the
#                                         devs-auto-signed-in snapshot on a free port (signed in,
#                                         no new grant)
#   session.sh up      <run> --fresh      create AVD <run> from putio-phone's image and boot it
#                                         (signed out; sign in with scripts/sign-in-devs-auto.sh)
#   session.sh install <run>              install the built production debug APK (adb install -r)
#   session.sh down    <run>              force-stop the app and stop the run's emulator; --fresh
#                                         runs also delete AVD <run>. .evidence/<run>/ stays
#
# Both print or record the serial in .evidence/<run>/run.env. Only that serial
# is ever stopped, and only while it still is this run's emulator; other
# emulators and the attached phones are never touched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
PACKAGE="${PUTIO_VERIFY_PACKAGE:-io.put.putio.mobile.debug}"
APK="${ROOT}/mobile/build/outputs/apk/production/debug/mobile-production-debug.apk"
SHARED_AVD="putio-phone"
SNAPSHOT="devs-auto-signed-in"
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
ADB="${ANDROID_HOME}/platform-tools/adb"
EMULATOR="${ANDROID_HOME}/emulator/emulator"
BOOT_TIMEOUT="${PUTIO_VERIFY_BOOT_TIMEOUT:-300}"

die() { echo "session.sh: $*" >&2; exit 1; }
log() { echo "[$(date +%H:%M:%S)] $*" >&2; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }

cmd="${1:-}"; run="${2:-}"; mode_arg="${3:-}"
[[ -n "${cmd}" && "${run}" =~ ^[A-Za-z0-9._-]+$ ]] || usage
DIR="${ROOT}/.evidence/${run}"
ENV_FILE="${DIR}/run.env"

env_value() { [[ -f "${ENV_FILE}" ]] && sed -n "s/^$1=//p" "${ENV_FILE}" | tail -1; }

# Succeeds while <serial> is a running emulator of <avd> served by <pid> (when known).
is_ours() {
  local serial="$1" avd="$2" pid="${3:-}" name
  [[ -n "${serial}" && "$("${ADB}" -s "${serial}" get-state 2>/dev/null)" == "device" ]] || return 1
  name="$("${ADB}" -s "${serial}" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  [[ "${name}" == "${avd}" ]] || return 1
  [[ -z "${pid}" ]] && return 0
  ps -o command= -p "${pid}" 2>/dev/null | grep -q -- "-port ${serial#emulator-}"
}

owns_run_serial() { is_ours "$(env_value SERIAL)" "$(env_value AVD)" "$(env_value PID)"; }

# First even console port in 5554-5584 that adb does not list and nothing listens on.
free_port() {
  local port listed
  listed="$("${ADB}" devices | awk '{ print $1 }')"
  for ((port = 5554; port <= 5584; port += 2)); do
    grep -qx "emulator-${port}" <<<"${listed}" && continue
    nc -z 127.0.0.1 "${port}" 2>/dev/null && continue
    nc -z 127.0.0.1 $((port + 1)) 2>/dev/null && continue
    echo "${port}"; return 0
  done
  return 1
}

write_env() {
  {
    echo "RUN=${run}"
    echo "MODE=$1"
    echo "AVD=$2"
    echo "SERIAL=$3"
    echo "PID=$4"
    echo "PACKAGE=${PACKAGE}"
    echo "STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "STATE=up"
  } > "${ENV_FILE}"
}

boot_snapshot() {
  [[ -d "${HOME}/.android/avd/${SHARED_AVD}.avd/snapshots/${SNAPSHOT}" ]] ||
    die "no ${SNAPSHOT} snapshot on ${SHARED_AVD}; use --fresh and sign in with scripts/sign-in-devs-auto.sh"
  local port serial pid logfile deadline
  port="$(free_port)" || die "no free emulator port in 5554-5584"
  serial="emulator-${port}"
  logfile="${DIR}/emulator-${port}.log"
  log "booting read-only ${SHARED_AVD} from ${SNAPSHOT} on ${serial} (log: ${logfile#"${ROOT}/"})"
  # -read-only keeps the shared AVD unchanged, so parallel instances can load the same snapshot.
  nohup "${EMULATOR}" -avd "${SHARED_AVD}" -read-only -snapshot "${SNAPSHOT}" -no-snapshot-save \
    -port "${port}" -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim > "${logfile}" 2>&1 &
  pid=$!
  write_env snapshot "${SHARED_AVD}" "${serial}" "${pid}"
  deadline=$((SECONDS + BOOT_TIMEOUT))
  until [[ "$("${ADB}" -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    kill -0 "${pid}" 2>/dev/null || { sed -i '' 's/^STATE=up$/STATE=failed/' "${ENV_FILE}"; die "emulator exited; see ${logfile}"; }
    ((SECONDS < deadline)) || { "${ADB}" -s "${serial}" emu kill >/dev/null 2>&1 || kill "${pid}"; die "boot timed out after ${BOOT_TIMEOUT}s"; }
    sleep 2
  done
  is_ours "${serial}" "${SHARED_AVD}" "${pid}" || die "${serial} is not the instance this run started"
  grep -qiE "fail.*snapshot|snapshot.*fail|cold boot" "${logfile}" &&
    log "WARNING: ${SNAPSHOT} may not have loaded; the app could be signed out (Doctor will say)"
  log "booted ${serial} (pid ${pid}) from ${SNAPSHOT}"
  echo "${serial}"
}

boot_fresh() {
  local serial
  "${ROOT}/scripts/emulator.sh" create phone --name "${run}" >&2
  serial="$("${ROOT}/scripts/emulator.sh" boot phone --headless --name "${run}")" || die "boot failed; see .evidence/logs/"
  is_ours "${serial}" "${run}" || die "${serial} is not AVD ${run}; leaving it alone"
  write_env fresh "${run}" "${serial}" ""
  echo "${serial}"
}

case "${cmd}" in
  up)
    mkdir -p "${DIR}"
    if [[ "$(env_value STATE)" == "up" ]] && owns_run_serial; then
      env_value SERIAL; exit 0
    fi
    case "${mode_arg}" in
      --snapshot) boot_snapshot ;;
      --fresh) boot_fresh ;;
      *) usage ;;
    esac
    ;;
  install)
    serial="$(env_value SERIAL)"
    owns_run_serial || die "no running emulator for run ${run}; run session.sh up ${run} --snapshot"
    [[ -f "${APK}" ]] || die "missing ${APK}; run ./gradlew --max-workers=6 :mobile:assembleProductionDebug"
    "${ADB}" -s "${serial}" install -r "${APK}" >&2
    echo "installed $(shasum -a 256 "${APK}" | cut -c1-12) on ${serial}"
    ;;
  down)
    serial="$(env_value SERIAL || true)"
    mode="$(env_value MODE || true)"
    pid="$(env_value PID || true)"
    if owns_run_serial; then
      "${ADB}" -s "${serial}" shell am force-stop "${PACKAGE}" || true
      if [[ "${mode}" == "fresh" ]]; then
        "${ROOT}/scripts/emulator.sh" stop "${serial}" >&2
      else
        "${ADB}" -s "${serial}" emu kill >/dev/null 2>&1 || true
        for _ in $(seq 30); do kill -0 "${pid}" 2>/dev/null || break; sleep 1; done
        kill -0 "${pid}" 2>/dev/null && { log "pid ${pid} still running after emu kill; stopping it"; kill "${pid}"; sleep 2; }
      fi
    else
      log "no running emulator for run ${run}"
    fi
    [[ "${mode}" == "fresh" ]] && "${ROOT}/scripts/emulator.sh" delete phone --name "${run}" >&2
    [[ -f "${ENV_FILE}" ]] && sed -i '' 's/^STATE=up$/STATE=down/' "${ENV_FILE}"
    for _ in $(seq 15); do "${ADB}" devices | grep -q "^${serial:-none}[[:space:]]" || break; sleep 1; done
    "${ADB}" devices | grep -q "^${serial:-none}[[:space:]]" && die "${serial} still listed by adb"
    echo "down ${run}; evidence kept in .evidence/${run}/"
    ;;
  *) usage ;;
esac
