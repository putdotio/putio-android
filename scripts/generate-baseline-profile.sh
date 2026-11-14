#!/usr/bin/env bash
# Regenerates the TV app's baseline profile on one emulator, signed in as
# devs-auto. `scripts/generate-baseline-profile.sh --help` documents it.

usage() {
  cat <<'EOF'
Usage: scripts/generate-baseline-profile.sh <emulator-serial>

Runs :tv:generateBaselineProfile on that emulator alone, approves each
activation code the generator logs (tag PutioBaselineProfile) as devs-auto
with the putio CLI, and writes tv/src/main/generated/baselineProfiles/.
The generator profiles two journeys, each linking with its own code and
signing out at the end, which revokes its grant: linking to Home, and cold
starts that restore the stored token. Only the stored-token starts feed the
startup profile. Commit the regenerated files.

  emulator-serial   a running emulator at API 34 or newer, e.g. emulator-5554;
                    older ones reinstall the app before profiling, which
                    drops the stored token; the generator presses Home, so
                    use one you booted:
                    scripts/emulator.sh boot tv --headless --name <your-avd>

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

serial="${1:-}"
[[ $# -eq 1 && "${serial}" =~ ^emulator-[0-9]+$ ]] || { usage >&2; exit 64; }

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
build_log="$(mktemp)"
ANDROID_SERIAL="${serial}" ./gradlew -q -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :tv:generateBaselineProfile >"${build_log}" 2>&1 &
gradle_pid=$!
sign_outs="$(mktemp)"
trap 'kill "${gradle_pid}" 2>/dev/null || true; rm -f "${build_log}" "${sign_outs}"' EXIT

poll() {
  local lines
  lines="$(adb_ logcat -d -s "${TAG}:I")"
  grep "signed out$" <<<"${lines}" >>"${sign_outs}" || true
  sed -n 's/.*activation code \([A-Z0-9]*\)$/\1/p' <<<"${lines}" | tail -1
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
poll >/dev/null
signed_out="$(sort -u "${sign_outs}" | wc -l | tr -d ' ')"
((signed_out >= 2)) || die "a journey did not sign out; sign out of io.put.putio on ${serial} to revoke its grant"
log "wrote tv/src/main/generated/baselineProfiles/"
