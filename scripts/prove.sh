#!/usr/bin/env bash
# One-command launch proof: boot emulator, install the debug APK, run the
# instrumented smoke test (LaunchSmokeTest via connectedAndroidTest), capture
# evidence, tear down.
#
#   scripts/prove.sh <mobile|tv> [flags]
#
# Flags:
#   --record        also capture a screen recording (default: screenshot only)
#   --seconds N     recording length (default 10)
#   --keep          leave the emulator running after the proof
#   --ephemeral     create a throwaway AVD for this run and delete it after
#   --window        show the emulator window (default: headless)
#   --skip-build    skip the explicit assemble (connectedAndroidTest still
#                   builds incrementally)
#
# Exit codes: 0 proof passed · 1 proof failed · 64 usage · 70 cleanup failed
# · 130/143 interrupted by SIGINT/SIGTERM (after stopping owned emulators)
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

# evidence.sh needs ffprobe for its capture gates; fail before booting
# anything rather than after a full verification.
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
BOOT_PID=""
PROOF_OK=0

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
    BOOT_PID="$(awk '{print $2}' "${BOOT_STATE}")"
    OWNED=1
  fi
  # Ownership is ultimately the spawned pid: if our emulator process is dead,
  # whatever answers on the serial (a same-AVD boot that won a port race)
  # is not ours to stop — but our corpse must still be confirmed gone from
  # adb before this run may report success.
  if [[ "${OWNED}" == "1" && -n "${BOOT_PID:-}" ]] && ! kill -0 "${BOOT_PID}" 2>/dev/null; then
    log "owned emulator process ${BOOT_PID} already exited; ${SERIAL} not ours to stop"
    OWNED=0
    if ! wait_serial_gone "${SERIAL}" 15; then
      state="$("${ADB}" devices 2>/dev/null | awk -v s="${SERIAL}" '$1 == s {print $2}')"
      if [[ "${state}" == "device" ]]; then
        log "${SERIAL} now serves another invocation; leaving it"
      else
        log "ERROR: dead emulator ${SERIAL} still listed (${state:-absent?}) in adb devices"
        cleanup_failed=1
      fi
    fi
  fi
  rm -f "${rec_out:-}" "${BOOT_STATE:-}" "${smoke_out:-}"
  if [[ "${KEEP}" == "1" && "${code}" -eq 0 ]]; then
    log "--keep: leaving ${SERIAL:-<none>} running"
  else
    if [[ "${KEEP}" == "1" ]]; then
      log "--keep ignored: proof did not succeed (exit ${code}); cleaning up"
    fi
    if [[ "${OWNED}" == "1" && -n "${SERIAL}" ]]; then
      # Guard against a boot port race: never emu-kill a serial that answers
      # for someone else's AVD.
      serial_avd="$("${ADB}" -s "${SERIAL}" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)"
      if [[ -n "${serial_avd}" && "${serial_avd}" != "${AVD_NAME}" ]]; then
        log "ERROR: owned serial ${SERIAL} answers for AVD ${serial_avd}, not ${AVD_NAME}; refusing to stop it"
        cleanup_failed=1
      else
        log "stopping owned emulator ${SERIAL}"
        "${ADB}" -s "${SERIAL}" emu kill >/dev/null 2>&1 || true
        if ! wait_serial_gone "${SERIAL}" 30; then
          log "ERROR: owned emulator ${SERIAL} still in adb devices after cleanup"
          cleanup_failed=1
        else
          log "owned emulator ${SERIAL} confirmed gone"
        fi
      fi
    fi
    if [[ "${CREATED_EPHEMERAL}" == "1" ]]; then
      log "deleting ephemeral AVD ${AVD_NAME}"
      "${AVDMANAGER}" delete avd --name "${AVD_NAME}" >/dev/null 2>&1 || cleanup_failed=1
    fi
  fi
  # The final marker is emitted only after teardown so a cleanup failure can
  # never leave both PASS and FAIL in machine-readable stdout.
  if [[ "${cleanup_failed}" == "1" ]]; then
    fail_marker
    exit 70
  fi
  if [[ "${code}" -eq 0 && "${PROOF_OK}" == "1" ]]; then
    echo "PROOF PASS ${FLAVOR}"
    exit 0
  fi
  fail_marker
  [[ "${code}" -ne 0 ]] || code=1
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

# emulator.sh boot owns the whole path: reuse detection, boot-complete and
# package-manager readiness, and device prep. Ownership comes from the state
# file, written only when boot spawned a process; on reuse it stays empty
# and this invocation must not stop the emulator.
boot_flags=()
[[ "${HEADLESS}" == "1" ]] && boot_flags+=(--headless)
BOOT_STATE="$(mktemp)"
SERIAL="$(PUTIO_BOOT_STATE_FILE="${BOOT_STATE}" "${REPO_ROOT}/scripts/emulator.sh" boot "${PROFILE}" --name "${AVD_NAME}" ${boot_flags[@]+"${boot_flags[@]}"} | tail -1)"
[[ "${SERIAL}" == emulator-* ]] || die "emulator boot did not return a serial (got '${SERIAL}')"
if [[ -s "${BOOT_STATE}" ]]; then
  OWNED=1
  BOOT_PID="$(awk '{print $2}' "${BOOT_STATE}")"
else
  log "reusing running emulator ${SERIAL} (not owned; will not be stopped)"
fi
rm -f "${BOOT_STATE}"; BOOT_STATE=""
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

rec_pid=""
rec_out=""

# --- known-good verification -------------------------------------------------
# The launch proof is the instrumented smoke test (LaunchSmokeTest): RESUMED
# state, a 3 s stability window, and a real-pixel luma assertion, all through
# platform test APIs instead of dumpsys/pidof parsing. A crash or ANR fails
# the instrumentation. One retry covers the cold-boot black-render flake,
# which heals once post-boot churn settles.
case "${FLAVOR}" in
  mobile) CONNECTED_TASK=":app:connectedMobileDebugAndroidTest" ;;
  tv) CONNECTED_TASK=":app:connectedTvDebugAndroidTest" ;;
esac

smoke_out="$(mktemp)"

run_smoke_test() {
  (cd "${REPO_ROOT}" && ANDROID_SERIAL="${SERIAL}" ./gradlew "${CONNECTED_TASK}") >"${smoke_out}" 2>&1
}

# Only the black-render assertion earns a retry; crashes, ANRs, and other
# launch failures are terminal so a flaky-looking pass cannot hide them.
black_render_failure() {
  grep -q "screen is effectively black" "${smoke_out}" 2>/dev/null || \
    grep -rq "screen is effectively black" "${REPO_ROOT}/app/build/outputs/androidTest-results" 2>/dev/null
}

# Stale results from an earlier run must not classify this run's failure.
rm -rf "${REPO_ROOT}/app/build/outputs/androidTest-results"

log "running instrumented launch proof (${CONNECTED_TASK}) on ${SERIAL}"
if ! run_smoke_test; then
  if black_render_failure; then
    log "black-render assertion failed; settling 10s and retrying once"
    sleep 10
    run_smoke_test || { tail -30 "${smoke_out}" >&2; die "instrumented launch proof failed twice; see app/build/reports/androidTests"; }
  else
    tail -30 "${smoke_out}" >&2
    die "instrumented launch proof failed (not a render flake); see app/build/reports/androidTests"
  fi
fi
rm -f "${smoke_out}"; smoke_out=""
log "instrumented launch proof passed"

# --- evidence ----------------------------------------------------------------
# The proof above verified an ActivityScenario launch that AGP uninstalled
# afterward; the captures come from a separate launcher-style launch that
# needs its own health check: our activity top-resumed, clean crash buffer,
# no app ANR since the launch. The luma gates alone would miss a crash into
# a bright system dialog.
evidence_launch_healthy() {
  # Fail closed: a failed adb query is indistinguishable from "no crashes"
  # only if the query status is ignored, so it isn't.
  local resumed crash_log anr_log crashes anrs
  resumed="$("${ADB}" -s "${SERIAL}" shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|ResumedActivity' | head -2 || true)"
  grep -qF "${APP_ID}" <<<"${resumed}" || { log "evidence launch not top-resumed"; return 1; }
  crash_log="$("${ADB}" -s "${SERIAL}" logcat -b crash -d 2>/dev/null)" || { log "crash buffer query failed"; return 1; }
  crashes="$(grep -F "${APP_ID}" <<<"${crash_log}" || true)"
  [[ -z "${crashes}" ]] || { log "evidence launch crashed: $(head -1 <<<"${crashes}")"; return 1; }
  anr_log="$("${ADB}" -s "${SERIAL}" logcat -b main -b system -d 2>/dev/null)" || { log "logcat query failed"; return 1; }
  anrs="$(grep -F "ANR in ${APP_ID}" <<<"${anr_log}" || true)"
  [[ -z "${anrs}" ]] || { log "evidence launch ANRed: $(head -1 <<<"${anrs}")"; return 1; }
}

log "reinstalling ${APK##*/} for the evidence launch"
install_out="$("${ADB}" -s "${SERIAL}" install -r "${APK}" 2>&1)" || die "reinstall failed: ${install_out}"
"${ADB}" -s "${SERIAL}" logcat -b crash -b main -b system -c || true
log "launching ${COMPONENT} for evidence"
start_out="$("${ADB}" -s "${SERIAL}" shell am start -W -n "${COMPONENT}" 2>&1)" || die "evidence launch failed: ${start_out}"
sleep 3
evidence_launch_healthy || die "evidence launch is not healthy"
shot="$("${REPO_ROOT}/scripts/evidence.sh" screenshot --serial "${SERIAL}" --label "${FLAVOR}-launch")" || \
  die "evidence screenshot failed its gate (quarantined in .evidence/)"
# Recheck after capture: a crash in the check-to-screencap window could
# otherwise publish a screenshot of whatever replaced the app.
if ! evidence_launch_healthy; then
  mv "${shot}" "${shot%.png}.unverified.png" 2>/dev/null || true
  die "evidence launch died during capture — screenshot quarantined as ${shot%.png}.unverified.png"
fi
echo "EVIDENCE ${shot}"

if [[ "${RECORD}" == "1" ]]; then
  # Recorded on the settled, render-verified system: a force-stop then cold
  # process relaunch under active capture. screenrecord only receives frames
  # on content changes, so the launch transition guarantees a playable clip
  # that shows the thing the proof claims — the app coming up.
  record_relaunch() {
    "${ADB}" -s "${SERIAL}" shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
    rec_out="$(mktemp)"
    "${REPO_ROOT}/scripts/evidence.sh" record --serial "${SERIAL}" --label "${FLAVOR}-launch" --seconds "${RECORD_SECONDS}" >"${rec_out}" 2>&1 &
    rec_pid=$!
    sleep 1
    start_out="$("${ADB}" -s "${SERIAL}" shell am start -W -n "${COMPONENT}" 2>&1)" || die "recorded relaunch failed: ${start_out}"
    if ! wait "${rec_pid}"; then
      log "recording capture output: $(cat "${rec_out}")"
      rm -f "${rec_out}"
      rec_pid=""
      return 1
    fi
    rec="$(tail -1 "${rec_out}")"
    rm -f "${rec_out}"
    rec_pid=""
  }

  log "recording a relaunch (${RECORD_SECONDS}s)"
  if ! record_relaunch; then
    # A screenrecord that failed or desynced from the relaunch (unready
    # encoder shortly after boot) gets one full extra cycle.
    log "recording cycle failed; retrying the full record+relaunch once"
    record_relaunch || die "recording capture failed twice"
  fi
  # The recording was already published by evidence.sh; if its relaunch
  # turns out unhealthy, neither the clip nor its companion screenshot may
  # stay under a publishable name.
  post_shot=""
  quarantine_rec() {
    mv "${rec}" "${rec%.mp4}.unverified.mp4" 2>/dev/null || true
    if [[ -n "${post_shot}" ]]; then
      mv "${post_shot}" "${post_shot%.png}.unverified.png" 2>/dev/null || true
    fi
    die "$1 — recording quarantined as ${rec%.mp4}.unverified.mp4"
  }
  evidence_launch_healthy || quarantine_rec "recorded relaunch is not healthy"
  post_shot="$("${REPO_ROOT}/scripts/evidence.sh" screenshot --serial "${SERIAL}" --label "${FLAVOR}-launch-after-record")" || \
    quarantine_rec "screen black or corrupt after recorded relaunch"
  evidence_launch_healthy || quarantine_rec "recorded relaunch died during capture"
  log "recorded relaunch rendered (${post_shot##*/})"
  echo "EVIDENCE ${rec}"
fi

# cleanup emits the final PROOF marker after teardown succeeds.
PROOF_OK=1
