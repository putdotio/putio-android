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
#   session.sh relaunch <run>             force-stop and start the app, wait for Files or Welcome;
#                                         taps Try again on "Couldn’t check your session" (4 min bound)
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
[[ -n "${cmd}" && "${run}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage
DIR="${ROOT}/.evidence/${run}"
ENV_FILE="${DIR}/run.env"

env_value() { [[ -f "${ENV_FILE}" ]] && sed -n "s/^$1=//p" "${ENV_FILE}" | tail -1; }

# Succeeds while <pid> is an emulator started with exactly `-avd <avd>` and
# `-port <port>` (whole arguments, not prefixes).
pid_runs() {
  local pid="$1" avd="$2" port="$3" command
  [[ -n "${pid}" ]] || return 1
  command="$(ps -o command= -p "${pid}" 2>/dev/null)" || return 1
  python3 -I -c '
import sys
args = sys.argv[1].split()
pairs = {args[i]: args[i + 1] for i in range(len(args) - 1) if args[i] in ("-avd", "-port")}
sys.exit(0 if pairs.get("-avd") == sys.argv[2] and pairs.get("-port") == sys.argv[3] else 1)
' "${command}" "${avd}" "${port}"
}

# Succeeds while <serial> is a running emulator of <avd> served by <pid> (when known).
is_ours() {
  local serial="$1" avd="$2" pid="${3:-}" name
  [[ -n "${serial}" && "$("${ADB}" -s "${serial}" get-state 2>/dev/null)" == "device" ]] || return 1
  name="$("${ADB}" -s "${serial}" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  [[ "${name}" == "${avd}" ]] || return 1
  [[ -z "${pid}" ]] && return 0
  pid_runs "${pid}" "${avd}" "${serial#emulator-}"
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

# Succeeds when <pid>, or a process it started, listens on console <port>.
listens() {
  local pid="$1" port="$2" listener
  for listener in $(lsof -nP -iTCP:"${port}" -sTCP:LISTEN -t 2>/dev/null); do
    [[ "${listener}" == "${pid}" || "$(ps -o ppid= -p "${listener}" | tr -d ' ')" == "${pid}" ]] && return 0
  done
  return 1
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
    # Another boot may hold this serial after a port race; stop only the process this run started.
    ((SECONDS < deadline)) || { kill "${pid}"; sed -i '' 's/^STATE=up$/STATE=failed/' "${ENV_FILE}"; die "boot timed out after ${BOOT_TIMEOUT}s"; }
    sleep 2
  done
  is_ours "${serial}" "${SHARED_AVD}" "${pid}" || die "${serial} is not the instance this run started"
  # Two boots can pick the same free port; the console port's listener names the winner.
  listens "${pid}" "${port}" || { kill "${pid}"; sed -i '' 's/^STATE=up$/STATE=failed/' "${ENV_FILE}"; die "port ${port} belongs to another emulator; retry"; }
  # The restored app checks its session at once; give it a validated network first.
  local connectivity
  while :; do
    connectivity="$("${ADB}" -s "${serial}" shell dumpsys connectivity 2>/dev/null || true)"
    [[ "${connectivity}" == *NetworkAgentInfo*IS_VALIDATED* ]] && break
    ((SECONDS < deadline)) || die "no validated network on ${serial} after ${BOOT_TIMEOUT}s"
    sleep 2
  done
  grep -q "Successfully loaded snapshot '${SNAPSHOT}'" "${logfile}" ||
    log "WARNING: ${SNAPSHOT} did not load; this is a cold boot and the app may be signed out (Doctor will say)"
  log "booted ${serial} (pid ${pid}) from ${SNAPSHOT}"
  echo "${serial}"
}

boot_fresh() {
  local serial
  # emulator.sh reuses an existing AVD and its running instance; a fresh run must own both.
  case "${run}" in putio-phone | putio-tv | putio-google-tv) die "${run} is a shared AVD name; pick another run name" ;; esac
  # The emulator's own list follows ANDROID_AVD_HOME and ANDROID_USER_HOME.
  ! "${EMULATOR}" -list-avds 2>/dev/null | grep -qx "${run}" ||
    die "AVD ${run} already exists and this run did not create it; pick another run name"
  "${ROOT}/scripts/emulator.sh" create phone --name "${run}" >&2
  # Record the AVD before booting, so down deletes it even when the boot fails.
  write_env fresh "${run}" "" ""
  local boot_log pid
  boot_log="${DIR}/boot-fresh.log"
  # emulator.sh writes "<serial> <pid>" here once it starts the process, so
  # down finds an emulator even if this script dies before recording it.
  serial="$(PUTIO_BOOT_STATE_FILE="${DIR}/boot-state" "${ROOT}/scripts/emulator.sh" boot phone --headless --name "${run}" 2>"${boot_log}")" ||
    { cat "${boot_log}" >&2; die "boot failed; run session.sh down ${run}"; }
  cat "${boot_log}" >&2
  pid="$(sed -n 's/.*booted .* on .* (pid \([0-9]*\)).*/\1/p' "${boot_log}" | tail -1)"
  is_ours "${serial}" "${run}" "${pid}" || die "${serial} is not AVD ${run}; leaving it alone"
  write_env fresh "${run}" "${serial}" "${pid}"
  echo "${serial}"
}

case "${cmd}" in
  up)
    mkdir -p "${DIR}"
    if [[ "$(env_value STATE)" == "up" ]] && owns_run_serial; then
      env_value SERIAL; exit 0
    fi
    case "$(env_value STATE)" in
      up | failed) die "run.env says run ${run} may still hold an emulator or AVD; run session.sh down ${run} first" ;;
    esac
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
  relaunch)
    serial="$(env_value SERIAL)"
    owns_run_serial || die "no running emulator for run ${run}"
    "${ADB}" -s "${serial}" shell am force-stop "${PACKAGE}"
    "${ADB}" -s "${serial}" shell am start -W -n "${PACKAGE}/io.putdotio.android.MainActivity" >/dev/null
    ui="$(dirname "${BASH_SOURCE[0]}")/ui.sh"
    # Right after a snapshot load the app can fail to check its session for a
    # minute or two; its "Couldn’t check your session" screen keeps the session
    # and offers Try again.
    deadline=$((SECONDS + ${PUTIO_VERIFY_SESSION_TIMEOUT:-240}))
    until "${ui}" find "${serial}" --match '^(Search your files|Welcome!|Welcome back!)$' >/dev/null 2>&1; do
      ((SECONDS < deadline)) || die "the app showed neither Files nor Welcome; run ui.sh dump ${serial} to see why"
      if "${ui}" find "${serial}" --text "Couldn’t check your session" >/dev/null 2>&1; then
        log "session check unavailable; tapping Try again"
        "${ui}" tap "${serial}" --text "Try again" || true
        sleep 8
      fi
      sleep 2
    done
    echo "relaunched ${PACKAGE} on ${serial}"
    ;;
  down)
    serial="$(env_value SERIAL || true)"
    mode="$(env_value MODE || true)"
    pid="$(env_value PID || true)"
    avd="$(env_value AVD || true)"
    if [[ -z "${serial}" && -f "${DIR}/boot-state" ]]; then
      read -r serial pid < "${DIR}/boot-state" || true
    fi
    # The recorded pid is still ours while it runs this AVD (and, for a shared
    # snapshot AVD, this port), even when adb lists the serial offline.
    pid_ours() { pid_runs "${pid}" "${avd}" "${serial#emulator-}"; }
    if owns_run_serial; then
      "${ADB}" -s "${serial}" shell am force-stop "${PACKAGE}" || true
      if [[ "${mode}" == "fresh" ]]; then
        "${ROOT}/scripts/emulator.sh" stop "${serial}" >&2
      else
        "${ADB}" -s "${serial}" emu kill >/dev/null 2>&1 || true
      fi
    fi
    if pid_ours; then
      for _ in $(seq 30); do pid_ours || break; sleep 1; done
      pid_ours && { log "pid ${pid} still serves ${serial}; stopping it"; kill "${pid}"; sleep 3; }
      pid_ours && die "pid ${pid} still serves ${serial}; run.env stays up"
    fi
    if [[ -n "${serial}" ]]; then
      for _ in $(seq 15); do "${ADB}" devices | grep -q "^${serial}[[:space:]]" || break; sleep 1; done
      if "${ADB}" devices | grep -q "^${serial}[[:space:]]" && { owns_run_serial || pid_ours; }; then
        die "${serial} is still up; run.env stays up"
      fi
    fi
    if [[ "${mode}" == "fresh" ]]; then
      "${ROOT}/scripts/emulator.sh" delete phone --name "${run}" >&2 || die "could not delete AVD ${run}; run.env stays up"
    fi
    [[ -f "${ENV_FILE}" ]] && sed -i '' -E 's/^STATE=(up|failed)$/STATE=down/' "${ENV_FILE}"
    echo "down ${run}; evidence kept in .evidence/${run}/"
    ;;
  *) usage ;;
esac
