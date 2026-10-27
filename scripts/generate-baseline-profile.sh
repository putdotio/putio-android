#!/usr/bin/env bash
# Regenerates mobile's Baseline Profile on a device. `scripts/generate-baseline-profile.sh --help`
# documents it.

usage() {
  cat <<'EOF'
Usage: scripts/generate-baseline-profile.sh <serial> <folder-id> <subfolder-name>

Writes mobile/src/main/baseline-prof.txt from MobileBaselineProfileGenerator
(:baselineprofile), which starts the app, switches tabs, opens and closes
<subfolder-name> inside <folder-id> and scrolls that folder. Release and
nightly builds embed the profile, so ART compiles those paths ahead of time.

  serial          an API 33+ device or emulator
  folder-id       a devs-auto folder holding the subfolder and enough rows to
                  scroll; make it with the verify skill's fixture.sh
  subfolder-name  the name of a folder row in it

Steps: build nightly debug unminified (-PputioBaselineProfileTarget=true), so
the profile names source classes, and the generator; install both; sign in
with scripts/sign-in-devs-auto.sh; run the generator with am instrument (about
ten minutes); pull its profile; uninstall the generator.

The unminified nightly debug build stays installed and signed in; install a
normal one afterwards to measure. Needs what sign-in-devs-auto.sh needs.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TARGET="io.put.putio.nightly.mobile.debug"
GENERATOR="io.putdotio.android.baselineprofile"
TEST_CLASS="${GENERATOR}.MobileBaselineProfileGenerator"
DEVICE_OUTPUT="/sdcard/Android/media/${GENERATOR}"
PROFILE="${REPO_ROOT}/mobile/src/main/baseline-prof.txt"

serial="${1:-}"
folder="${2:-}"
child="${3:-}"
[[ -n "${serial}" && $# -eq 3 ]] || die "usage: scripts/generate-baseline-profile.sh <serial> <folder-id> <subfolder-name>; see --help"
[[ "${folder}" =~ ^[1-9][0-9]*$ ]] || die "folder-id must be a positive folder id, got '${folder}'"
# The name crosses adb's remote shell inside single quotes.
[[ "${child}" =~ ^[A-Za-z0-9\ ._-]+$ ]] || die "subfolder-name may hold letters, digits, spaces, dots, dashes and underscores"

require_sdk_root
adb_() { "${ADB}" -s "${serial}" "$@"; }
[[ "$(adb_ get-state 2>/dev/null)" == "device" ]] || die "${serial} is not a running device"
(( $(adb_ shell getprop ro.build.version.sdk | tr -d '\r') >= 33 )) || die "${serial} must run API 33 or newer"

log "building unminified nightly debug and the generator"
(cd "${REPO_ROOT}" && ./gradlew -q :mobile:assembleNightlyDebug :baselineprofile:assembleNightlyDebug \
  -PputioBaselineProfileTarget=true)
adb_ install -r "${REPO_ROOT}/mobile/build/outputs/apk/nightly/debug/mobile-nightly-debug.apk" >/dev/null
adb_ install -r -t "${REPO_ROOT}/baselineprofile/build/outputs/apk/nightly/debug/baselineprofile-nightly-debug.apk" >/dev/null
"${REPO_ROOT}/scripts/sign-in-devs-auto.sh" "${serial}" "${TARGET}" >/dev/null

adb_ shell rm -rf "${DEVICE_OUTPUT}"
# Uninstalling also removes the generator's output, so it runs only after the pull.
trap 'adb_ uninstall "${GENERATOR}" >/dev/null 2>&1 || true' EXIT
log "collecting; this takes about ten minutes"
result="$(adb_ shell "am instrument -w -r -e class ${TEST_CLASS} -e putioProfileFolder ${folder} \
  -e putioProfileChild '${child}' ${GENERATOR}/androidx.test.runner.AndroidJUnitRunner")"
# am instrument exits 0 on a failed or empty run, so only its summary line proves the collection.
grep -q '^OK (1 test)' <<<"${result}" || { printf '%s\n' "${result}" | tail -20 >&2; die "the generator did not pass"; }

remote="$(adb_ shell ls "${DEVICE_OUTPUT}" | tr -d '\r' | grep -E '^MobileBaselineProfileGenerator_generate-baseline-prof\.txt$')" ||
  die "the generator wrote no profile to ${DEVICE_OUTPUT}"
adb_ pull "${DEVICE_OUTPUT}/${remote}" "${PROFILE}" >/dev/null
log "wrote $(wc -l <"${PROFILE}" | tr -d ' ') rules to mobile/src/main/baseline-prof.txt"
