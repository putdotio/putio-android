#!/usr/bin/env bash
# Regenerates the TV or mobile app's baseline profile on one emulator, signed
# in as devs-auto. `scripts/generate-baseline-profile.sh --help` documents it.

usage() {
  cat <<'EOF'
Usage: scripts/generate-baseline-profile.sh [tv|mobile] <emulator-serial>

Runs :tv:generateBaselineProfile (default) or :mobile:generateBaselineProfile
on that emulator alone, approves the activation code the generator logs (tag
PutioBaselineProfile) as devs-auto with the putio CLI, and writes
<app>/src/main/generated/baselineProfiles/.
Each generator links once and keeps that session across its signed-in journeys
so only stored-token cold starts feed the startup profile. It never signs out,
so no grant is revoked; uninstall the app or delete the emulator afterwards.
Commit the regenerated files.

  tv|mobile         app target to regenerate (defaults to tv)
  emulator-serial   a fresh emulator at API 34 or newer, e.g. emulator-5554;
                    older ones reinstall the app before profiling, which
                    drops the stored token; the generator presses Home, so
                    use one you booted:
                    scripts/emulator.sh boot tv --headless --name <your-avd>
                    scripts/emulator.sh boot phone --headless --name <your-avd>

Prerequisites: python3, and the putio CLI signed in:
putio auth login --profile devs-auto

Exit codes: 0 generated · 1 failed · 64 usage
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PROFILE="devs-auto"
TAG="PutioBaselineProfile"

if [[ $# -eq 1 ]]; then
  target="tv"
  serial="${1:-}"
elif [[ $# -eq 2 ]]; then
  target="${1:-}"
  serial="${2:-}"
else
  usage >&2
  exit 64
fi

case "${target}" in
  tv)
    gradle_task=":tv:generateBaselineProfile"
    test_package="io.putdotio.android.baselineprofile.tv"
    output_dir="tv/src/main/generated/baselineProfiles/"
    ;;
  mobile)
    gradle_task=":mobile:generateBaselineProfile"
    test_package="io.putdotio.android.baselineprofile.mobile"
    output_dir="mobile/src/main/generated/baselineProfiles/"
    ;;
  *)
    usage >&2
    exit 64
    ;;
esac

[[ "${serial}" =~ ^emulator-[0-9]+$ ]] || { usage >&2; exit 64; }

cli() { env -u PUTIO_CLI_TOKEN PUTIO_CLI_PROFILE="${PROFILE}" putio "$@"; }
json_field() { python3 -I -c 'import json, sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"; }

command -v python3 >/dev/null 2>&1 || die "python3 not on PATH"
command -v putio >/dev/null 2>&1 || die "putio CLI not on PATH; then run: putio auth login --profile ${PROFILE}"
status="$(env -u PUTIO_CLI_TOKEN putio auth status --profile "${PROFILE}" --output json 2>/dev/null)" ||
  die "putio auth status failed; run: putio auth login --profile ${PROFILE}"
[[ "$(json_field authenticated <<<"${status}")" == "True" ]] ||
  die "putio profile ${PROFILE} is not signed in; run: putio auth login --profile ${PROFILE}"
whoami="$(cli whoami --fields info --output json 2>/dev/null)" || die "putio whoami failed for ${PROFILE}"
[[ "$(python3 -I -c 'import json, sys; print(json.load(sys.stdin)["info"]["username"])' <<<"${whoami}")" == "${PROFILE}" ]] ||
  die "putio profile ${PROFILE} does not resolve to the ${PROFILE} account"

require_sdk_root
adb_() { "${ADB}" -s "${serial}" "$@"; }
[[ "$(adb_ get-state 2>/dev/null)" == "device" ]] || die "${serial} is not running"
sdk="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')"
((sdk >= 34)) || die "${serial} runs API ${sdk}; the generator needs API 34 or newer"

approve() {
  local code="$1" payload preview
  payload="{\"code\":\"${code}\"}"
  preview="$(cli auth approve --json "${payload}" --dry-run --output json 2>/dev/null)" ||
    die "putio auth approve --dry-run failed"
  [[ "$(python3 -I -c 'import json, sys; print(json.load(sys.stdin)["request"]["code"])' <<<"${preview}")" == "${code}" ]] ||
    die "putio auth approve dry run did not preview this code"
  cli auth approve --json "${payload}" --output json >/dev/null 2>&1 || die "putio auth approve failed"
  log "approved the generator's code as ${PROFILE}"
}

adb_ logcat -c
build_log=""
trap 'kill $(jobs -pr) 2>/dev/null || true; rm -f ${build_log:+"${build_log}"}' EXIT
build_log="$(mktemp)"
ANDROID_SERIAL="${serial}" ./gradlew -q -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "${gradle_task}" >"${build_log}" 2>&1 &
gradle_pid=$!

poll() {
  local pids pid
  pids="$(adb_ shell pidof "${test_package}" 2>/dev/null || true)"
  pid="$(printf '%s' "${pids}" | tr -d '\r' | awk '{print $1}')"
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 0
  adb_ logcat -d --pid="${pid}" -s "${TAG}:I" | sed -n 's/.*activation code \([A-Z0-9]*\)$/\1/p' | tail -1
}

approved=""
while kill -0 "${gradle_pid}" 2>/dev/null; do
  code="$(poll)"
  if [[ -n "${code}" && "${code}" != "${approved}" ]]; then
    approve "${code}"
    approved="${code}"
  fi
  sleep 2
done

if ! wait "${gradle_pid}"; then
  cat "${build_log}" >&2
  die "profile generation failed"
fi
log "wrote ${output_dir}"
