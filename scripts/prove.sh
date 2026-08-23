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

FLAVOR="${1:-}"; shift || { print_usage "${BASH_SOURCE[0]}"; exit 64; }

RECORD=0
RECORD_SECONDS=10
KEEP=0
EPHEMERAL=0
HEADLESS=1
SKIP_BUILD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --record) RECORD=1 ;;
    --seconds) RECORD_SECONDS="${2:?--seconds requires a value}"; shift ;;
    --keep) KEEP=1 ;;
    --ephemeral) EPHEMERAL=1 ;;
    --window) HEADLESS=0 ;;
    --skip-build) SKIP_BUILD=1 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# --keep exists for interactive follow-up on a healthy emulator; it must not
# defeat cleanup guarantees on failure or leak throwaway AVDs.
if [[ "${KEEP}" == "1" && "${EPHEMERAL}" == "1" ]]; then
  die "--keep and --ephemeral conflict: an ephemeral AVD is always deleted"
fi

# The pixel gate is part of the known-good contract; without ffprobe a black
# render would pass, making the exit code untrustworthy.
command -v ffprobe >/dev/null 2>&1 || die "ffprobe not found; run scripts/bootstrap.sh (installs ffmpeg)"

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
BOOT_STATE=""

fail_marker() { echo "PROOF FAIL ${FLAVOR}"; }

SIGNAL_CODE=""

cleanup() {
  local code=$?
  # $? in a signal trap is the last command's status, which may be 0;
  # an interrupted run must never report success.
  if [[ -n "${SIGNAL_CODE}" ]]; then code="${SIGNAL_CODE}"; fi
  trap - EXIT INT TERM
  local cleanup_failed=0
  # Reap a still-running background recorder before touching the emulator.
  if [[ -n "${rec_pid:-}" ]] && kill -0 "${rec_pid}" 2>/dev/null; then
    kill "${rec_pid}" 2>/dev/null || true
    wait "${rec_pid}" 2>/dev/null || true
  fi
  # An interrupt during the boot handoff can leave a spawned emulator that
  # neither emulator.sh (traps already cleared) nor OWNED tracks; the state
  # file emulator.sh wrote closes that window. Reuse never writes the file.
  if [[ "${OWNED}" != "1" && -n "${BOOT_STATE:-}" && -s "${BOOT_STATE}" ]]; then
    SERIAL="$(awk '{print $1}' "${BOOT_STATE}")"
    OWNED=1
  fi
  rm -f "${rec_out:-}" "${BOOT_STATE:-}"
  if [[ "${KEEP}" == "1" && "${code}" -eq 0 ]]; then
    log "--keep: leaving ${SERIAL:-<none>} running"
  else
    if [[ "${KEEP}" == "1" ]]; then
      log "--keep ignored: proof did not succeed (exit ${code}); cleaning up"
    fi
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
  BOOT_STATE="$(mktemp)"
  SERIAL="$(PUTIO_BOOT_STATE_FILE="${BOOT_STATE}" "${REPO_ROOT}/scripts/emulator.sh" boot "${PROFILE}" --name "${AVD_NAME}" ${boot_flags[@]+"${boot_flags[@]}"} | tail -1)"
  [[ "${SERIAL}" == emulator-* ]] || die "emulator boot did not return a serial (got '${SERIAL}')"
  OWNED=1
  rm -f "${BOOT_STATE}"; BOOT_STATE=""
fi
prepare_device "${SERIAL}"
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

"${ADB}" -s "${SERIAL}" shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
# Clear every buffer the verification reads, or stale crashes/ANRs from a
# previous run on a reused emulator fail a healthy launch.
"${ADB}" -s "${SERIAL}" logcat -b crash -b main -b system -c || true

rec_pid=""
rec_out=""

log "launching ${COMPONENT}"
"${ADB}" -s "${SERIAL}" shell am start -W -n "${COMPONENT}" >/dev/null

# --- known-good verification -------------------------------------------------
# Known-good state: the app process is alive, our activity is the top resumed
# activity, both still hold 3 seconds later, the crash buffer is empty, and
# logcat carries no app ANR. Runs after the initial launch and again after
# every relaunch (black-render retry, recorded relaunch).

# Probes must tolerate failing commands inside set -e/pipefail — a nonzero
# pidof or no-match grep would otherwise abort the retry loop on its first
# probe instead of polling.
app_pid() {
  "${ADB}" -s "${SERIAL}" shell pidof "${APP_ID}" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true
}
resumed_activity() {
  "${ADB}" -s "${SERIAL}" shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|ResumedActivity' | head -2 || true
}

verify_known_good() {
  local deadline pid pid_after crashes anr_log anrs system_anrs
  log "verifying launch state"
  deadline=$(( $(date +%s) + 30 ))
  pid=""
  while (( $(date +%s) < deadline )); do
    pid="$(app_pid)"
    if [[ -n "${pid}" ]] && grep -qF "${APP_ID}" <<<"$(resumed_activity)"; then
      break
    fi
    pid=""
    sleep 1
  done
  [[ -n "${pid}" ]] || die "app did not reach resumed state within 30s (pidof + dumpsys)"

  sleep 3
  pid_after="$(app_pid)"
  [[ "${pid_after}" == "${pid}" ]] || die "app process changed after launch (was ${pid}, now '${pid_after}') — crash-restart suspected"
  grep -qF "${APP_ID}" <<<"$(resumed_activity)" || die "app no longer top-resumed after stability window"
  crashes="$("${ADB}" -s "${SERIAL}" logcat -b crash -d 2>/dev/null | grep -F "${APP_ID}" || true)"
  [[ -z "${crashes}" ]] || die "crash buffer mentions ${APP_ID}: ${crashes}"
  anr_log="$("${ADB}" -s "${SERIAL}" logcat -b main -b system -d 2>/dev/null | grep -E 'ANR in [a-z][a-z0-9.]+' || true)"
  anrs="$(grep -F "ANR in ${APP_ID}" <<<"${anr_log}" || true)"
  [[ -z "${anrs}" ]] || die "app ANR detected: ${anrs}"
  system_anrs="$(grep -Fv "${APP_ID}" <<<"${anr_log}" | grep . || true)"
  [[ -z "${system_anrs}" ]] || log "WARNING: non-app ANRs on device (dialogs hidden, evidence unaffected): $(head -2 <<<"${system_anrs}")"
  log "launch verified: pid ${pid} resumed and stable, no app ANR"
}

verify_known_good

# --- evidence ----------------------------------------------------------------
# Pixel gate: a resumed process with a clean crash buffer can still render
# nothing — in the first ~minute after a cold headless boot the app window
# intermittently composites black. Mean luma below 16 means the screen is
# effectively black and the launch is not visually proven.
# Returns 0 and echoes the luma when rendered; returns 1 on black.
shot_luma() {
  local png="$1" luma
  luma="$(ffprobe -v error -f lavfi -i "movie=${png},signalstats" \
    -show_entries frame_tags=lavfi.signalstats.YAVG -of default=nk=1:nw=1 2>/dev/null | head -1 || true)"
  luma="${luma%%.*}"
  [[ "${luma}" =~ ^[0-9]+$ ]] || luma=0
  echo "${luma}"
  (( luma >= 16 ))
}

take_gated_screenshot() {
  shot="$("${REPO_ROOT}/scripts/evidence.sh" screenshot --serial "${SERIAL}" --label "${FLAVOR}-launch")"
  if ! command -v ffprobe >/dev/null 2>&1; then
    log "WARNING: ffprobe not found; cannot verify the screen actually rendered"
    return 0
  fi
  local luma
  if luma="$(shot_luma "${shot}")"; then
    log "screenshot luma ${luma} (render verified)"
    return 0
  fi
  mv "${shot}" "${shot%.png}.black.png"
  log "screen is black (mean luma ${luma}); quarantined ${shot%.png}.black.png"
  return 1
}

if ! take_gated_screenshot; then
  # The black-render window heals once the post-boot churn settles; one
  # relaunch retry distinguishes that flake from an app that never renders.
  log "black render detected; settling 10s and relaunching once"
  sleep 10
  "${ADB}" -s "${SERIAL}" shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
  "${ADB}" -s "${SERIAL}" shell am start -W -n "${COMPONENT}" >/dev/null
  verify_known_good
  take_gated_screenshot || die "screen still black after relaunch — app renders nothing"
fi
echo "EVIDENCE ${shot}"

if [[ "${RECORD}" == "1" ]]; then
  # Recorded on the settled, render-verified system: a force-stop then cold
  # process relaunch under active capture. screenrecord only receives frames
  # on content changes, so the launch transition guarantees a playable clip
  # that shows the thing the proof claims — the app coming up.
  log "recording a relaunch (${RECORD_SECONDS}s)"
  "${ADB}" -s "${SERIAL}" shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
  rec_out="$(mktemp)"
  "${REPO_ROOT}/scripts/evidence.sh" record --serial "${SERIAL}" --label "${FLAVOR}-launch" --seconds "${RECORD_SECONDS}" >"${rec_out}" 2>&1 &
  rec_pid=$!
  sleep 1
  "${ADB}" -s "${SERIAL}" shell am start -W -n "${COMPONENT}" >/dev/null
  if ! wait "${rec_pid}"; then
    log "recording capture output: $(cat "${rec_out}")"
    rm -f "${rec_out}"
    die "recording capture failed"
  fi
  rec="$(tail -1 "${rec_out}")"
  rm -f "${rec_out}"
  rec_pid=""
  verify_known_good
  take_gated_screenshot || die "screen black after recorded relaunch — recording untrustworthy"
  echo "EVIDENCE ${rec}"
fi

echo "PROOF PASS ${FLAVOR}"
