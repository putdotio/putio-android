#!/usr/bin/env bash
# One-command launch proof: boot emulator, install the debug APK, launch the
# app, verify it reached a known-good state, capture evidence, tear down.
#
#   scripts/prove.sh <mobile|tv> [flags]
#
# Flags:
#   --record        also capture a screen recording (default: screenshot only)
#   --seconds N     recording length (default 10)
#   --keep          leave the emulator running after the proof
#   --ephemeral     create a throwaway AVD for this run and delete it after
#   --window        show the emulator window (default: headless)
#   --skip-build    use the existing APK instead of running Gradle
#
# Exit codes: 0 proof passed · 1 proof failed · 64 usage · 70 cleanup failed
#
# Ownership contract:
#   - A running emulator for the target AVD is reused and never stopped.
#   - An emulator booted by this invocation is stopped by exact serial on
#     completion, failure, SIGINT, and SIGTERM, and its absence from
#     `adb devices` is confirmed before exit (cleanup failure exits 70).
#   - AVD registrations are deleted only when created here via --ephemeral.
#
# Machine-readable stdout markers: BOOTED <serial>, EVIDENCE <path>,
# PROOF PASS|FAIL <flavor>.
#
# Test hook: PUTIO_PROVE_FAIL_AT=after-boot|after-install injects a failure
# at that stage (used by scripts/test-lifecycle.sh).

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_sdk_root

FLAVOR="${1:-}"; shift || { sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 64; }

RECORD=0
RECORD_SECONDS=10
KEEP=0
EPHEMERAL=0
HEADLESS=1
SKIP_BUILD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --record) RECORD=1 ;;
    --seconds) RECORD_SECONDS="$2"; shift ;;
    --keep) KEEP=1 ;;
    --ephemeral) EPHEMERAL=1 ;;
    --window) HEADLESS=0 ;;
    --skip-build) SKIP_BUILD=1 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

case "${FLAVOR}" in
  mobile)
    PROFILE="phone"
    APP_ID="io.put.putio.mobile.debug"
    GRADLE_TASK=":app:assembleMobileDebug"
    APK="${REPO_ROOT}/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk"
    ;;
  tv)
    PROFILE="tv"
    APP_ID="io.put.putio.debug"
    GRADLE_TASK=":app:assembleTvDebug"
    APK="${REPO_ROOT}/app/build/outputs/apk/tv/debug/app-tv-debug.apk"
    ;;
  *) die "unknown flavor '${FLAVOR}' (expected mobile|tv)" ;;
esac
COMPONENT="${APP_ID}/io.putdotio.android.MainActivity"

AVD_NAME="$(avd_name_for "${PROFILE}")"
CREATED_EPHEMERAL=0
if [[ "${EPHEMERAL}" == "1" ]]; then
  AVD_NAME="putio-${PROFILE}-eph-$(date +%s)-$$"
fi

OWNED=0
SERIAL=""

fail_marker() { echo "PROOF FAIL ${FLAVOR}"; }

SIGNAL_CODE=""

cleanup() {
  local code=$?
  # $? in a signal trap is the last command's status, which may be 0;
  # an interrupted run must never report success.
  if [[ -n "${SIGNAL_CODE}" ]]; then code="${SIGNAL_CODE}"; fi
  trap - EXIT INT TERM
  local cleanup_failed=0
  if [[ "${KEEP}" == "1" ]]; then
    log "--keep: leaving ${SERIAL:-<none>} running"
  else
    if [[ "${OWNED}" == "1" && -n "${SERIAL}" ]]; then
      log "stopping owned emulator ${SERIAL}"
      "${ADB}" -s "${SERIAL}" emu kill >/dev/null 2>&1 || true
      if ! wait_serial_gone "${SERIAL}" 30; then
        log "ERROR: owned emulator ${SERIAL} still in adb devices after cleanup"
        cleanup_failed=1
      else
        log "owned emulator ${SERIAL} confirmed gone"
      fi
    fi
    if [[ "${CREATED_EPHEMERAL}" == "1" ]]; then
      log "deleting ephemeral AVD ${AVD_NAME}"
      "${AVDMANAGER}" delete avd --name "${AVD_NAME}" >/dev/null 2>&1 || cleanup_failed=1
    fi
  fi
  if [[ "${cleanup_failed}" == "1" ]]; then
    fail_marker
    exit 70
  fi
  if [[ "${code}" -ne 0 ]]; then
    fail_marker
  fi
  exit "${code}"
}
trap cleanup EXIT
trap 'SIGNAL_CODE=130 cleanup' INT
trap 'SIGNAL_CODE=143 cleanup' TERM

# --- build -------------------------------------------------------------------
if [[ "${SKIP_BUILD}" == "1" ]]; then
  [[ -f "${APK}" ]] || die "--skip-build but no APK at ${APK}"
else
  log "building ${GRADLE_TASK}"
  (cd "${REPO_ROOT}" && ./gradlew -q "${GRADLE_TASK}")
  [[ -f "${APK}" ]] || die "build succeeded but APK missing at ${APK}"
fi

# --- emulator ----------------------------------------------------------------
if [[ "${EPHEMERAL}" == "1" ]]; then
  log "creating ephemeral AVD ${AVD_NAME}"
  "${REPO_ROOT}/scripts/emulator.sh" create "${PROFILE}" --name "${AVD_NAME}"
  CREATED_EPHEMERAL=1
fi

if [[ "${EPHEMERAL}" != "1" ]] && SERIAL="$(serial_for_avd "${AVD_NAME}")"; then
  log "reusing running emulator ${SERIAL} (not owned; will not be stopped)"
else
  boot_flags=()
  [[ "${HEADLESS}" == "1" ]] && boot_flags+=(--headless)
  SERIAL="$("${REPO_ROOT}/scripts/emulator.sh" boot "${PROFILE}" --name "${AVD_NAME}" ${boot_flags[@]+"${boot_flags[@]}"} | tail -1)"
  [[ "${SERIAL}" == emulator-* ]] || die "emulator boot did not return a serial (got '${SERIAL}')"
  OWNED=1
fi
echo "BOOTED ${SERIAL}"

if [[ "${PUTIO_PROVE_FAIL_AT:-}" == "after-boot" ]]; then
  die "injected failure: after-boot"
fi

# --- install + launch --------------------------------------------------------
log "installing ${APK##*/} on ${SERIAL}"
install_out="$("${ADB}" -s "${SERIAL}" install -r "${APK}" 2>&1)" || die "install failed: ${install_out}"

if [[ "${PUTIO_PROVE_FAIL_AT:-}" == "after-install" ]]; then
  die "injected failure: after-install"
fi

log "launching ${COMPONENT}"
"${ADB}" -s "${SERIAL}" logcat -b crash -c || true
"${ADB}" -s "${SERIAL}" shell am start -W -n "${COMPONENT}" >/dev/null

# --- known-good verification -------------------------------------------------
# Known-good state: the app process is alive, our activity is the top resumed
# activity, and both still hold 3 seconds later with an empty crash buffer.
log "verifying launch state"
deadline=$(( $(date +%s) + 30 ))
pid=""
while (( $(date +%s) < deadline )); do
  pid="$("${ADB}" -s "${SERIAL}" shell pidof "${APP_ID}" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  resumed="$("${ADB}" -s "${SERIAL}" shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|ResumedActivity' | head -2)"
  if [[ -n "${pid}" ]] && grep -q "${APP_ID}" <<<"${resumed}"; then
    break
  fi
  pid=""
  sleep 1
done
[[ -n "${pid}" ]] || die "app did not reach resumed state within 30s (pidof + dumpsys)"

sleep 3
pid_after="$("${ADB}" -s "${SERIAL}" shell pidof "${APP_ID}" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
[[ "${pid_after}" == "${pid}" ]] || die "app process changed after launch (was ${pid}, now '${pid_after}') — crash-restart suspected"
crashes="$("${ADB}" -s "${SERIAL}" logcat -b crash -d 2>/dev/null | grep -F "${APP_ID}" || true)"
[[ -z "${crashes}" ]] || die "crash buffer mentions ${APP_ID}: ${crashes}"
log "launch verified: pid ${pid} resumed and stable"

# --- evidence ----------------------------------------------------------------
shot="$("${REPO_ROOT}/scripts/evidence.sh" screenshot --serial "${SERIAL}" --label "${FLAVOR}-launch")"
echo "EVIDENCE ${shot}"
if [[ "${RECORD}" == "1" ]]; then
  rec="$("${REPO_ROOT}/scripts/evidence.sh" record --serial "${SERIAL}" --label "${FLAVOR}-launch" --seconds "${RECORD_SECONDS}")"
  echo "EVIDENCE ${rec}"
fi

echo "PROOF PASS ${FLAVOR}"
