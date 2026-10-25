#!/usr/bin/env bash
# Read-only check: is this run's instance worth driving? Run it in the devbox
# copy (through devbox.sh run).
#
#   doctor.sh <run>
#
# Prints OK/FAIL per check, then DOCTOR PASS or DOCTOR FAIL (exit 1). Checks:
# the run's emulator is up and is the instance run.env records; API 37; the installed APK is the
# one this copy built; the putio CLI is devs-auto; the app is running and shows
# the signed-in shell. It taps nothing and changes no state.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
APK="${ROOT}/mobile/build/outputs/apk/production/debug/mobile-production-debug.apk"
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
ADB="${ANDROID_HOME}/platform-tools/adb"

run="${1:-}"
[[ "${run}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo "usage: doctor.sh <run>" >&2; exit 64; }
ENV_FILE="${ROOT}/.evidence/${run}/run.env"
failed=0
ok() { echo "OK   $*"; }
fail() { echo "FAIL $*"; failed=1; }
finish() { ((failed)) && { echo "DOCTOR FAIL"; exit 1; }; echo "DOCTOR PASS"; exit 0; }
# check <ok message> <fail message> <command...>
check() {
  local pass="$1" miss="$2"; shift 2
  if "$@"; then ok "${pass}"; else fail "${miss}"; fi
}
same() { [[ -n "$1" && "$1" == "$2" ]]; }

[[ -f "${ENV_FILE}" ]] || { fail "run.env: missing; run session.sh up ${run}"; finish; }
serial="$(sed -n 's/^SERIAL=//p' "${ENV_FILE}" | tail -1)"
package="$(sed -n 's/^PACKAGE=//p' "${ENV_FILE}" | tail -1)"
adb_() { "${ADB}" -s "${serial}" "$@" 2>/dev/null | tr -d '\r'; }
revision="${ROOT}/.evidence/${run}/revision.txt"

check "copy: $(paste -sd' ' "${revision}" 2>/dev/null)" "copy: no revision.txt; run devbox.sh sync ${run}" test -f "${revision}"

if [[ "$(adb_ get-state)" != "device" ]]; then
  fail "emulator: ${serial} is not running; run session.sh up ${run}"; finish
fi
want_avd="$(sed -n 's/^AVD=//p' "${ENV_FILE}" | tail -1)"
mode="$(sed -n 's/^MODE=//p' "${ENV_FILE}" | tail -1)"
pid="$(sed -n 's/^PID=//p' "${ENV_FILE}" | tail -1)"
avd="$(adb_ emu avd name | head -1)"
same "${avd}" "${want_avd}" || { fail "emulator: ${serial} is AVD '${avd}', not ${want_avd}; not ours"; finish; }
if [[ -n "${pid}" ]] && ! ps -o command= -p "${pid}" 2>/dev/null | grep -q -- "-port ${serial#emulator-}"; then
  fail "emulator: pid ${pid} no longer serves ${serial}; not ours"; finish
fi
ok "emulator: ${serial} is ${mode} AVD ${avd}${pid:+ (pid ${pid})}"
api="$(adb_ shell getprop ro.build.version.sdk)"
check "api: ${api}" "api: ${api}, expected 37" same "${api}" 37

installed="$(adb_ shell pm path "${package}" | sed -n 's/^package://p' | grep base.apk | head -1)"
if [[ -z "${installed}" ]]; then
  fail "build: ${package} not installed; run session.sh install ${run}"
elif [[ ! -f "${APK}" ]]; then
  fail "build: no local APK; run ./gradlew --max-workers=6 :mobile:assembleProductionDebug"
else
  device_sum="$(adb_ shell sha256sum "${installed}" | cut -d' ' -f1)"
  local_sum="$(shasum -a 256 "${APK}" | cut -d' ' -f1)"
  check "build: installed APK matches this copy (${local_sum:0:12})" \
    "build: installed APK differs from this copy; run session.sh install ${run}" same "${device_sum}" "${local_sum}"
fi

# shellcheck disable=SC2329  # invoked through check
cli_ok() {
  env -u PUTIO_CLI_TOKEN putio auth status --profile devs-auto --output json 2>/dev/null |
    python3 -I -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("authenticated") and d.get("profile")=="devs-auto" and d.get("source")=="profile" and d.get("apiBaseUrl")=="https://api.put.io" else 1)' 2>/dev/null &&
    env -u PUTIO_CLI_TOKEN PUTIO_CLI_PROFILE=devs-auto putio whoami --fields info --output json 2>/dev/null |
    python3 -I -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)["info"].get("username")=="devs-auto" else 1)' 2>/dev/null
}
check "cli: profile devs-auto is the devs-auto account" "cli: profile devs-auto is not the devs-auto account; run putio auth login --profile devs-auto" cli_ok

pid="$(adb_ shell pidof "${package}")"
if [[ -z "${pid}" ]]; then
  fail "app: not running; start it with am start -n ${package}/io.putdotio.android.MainActivity"
else
  ok "app: pid ${pid}"
  texts="$("$(dirname "${BASH_SOURCE[0]}")/ui.sh" dump "${serial}" | grep -oE '(text|content-desc)="[^"]*"' | sed -E 's/^[a-z-]+="(.*)"$/\1/')"
  tabs="$(grep -xE 'Files|History|Transfers|Downloads|Account' <<<"${texts}" | sort -u | wc -l | tr -d ' ')"
  if grep -qxE 'Welcome!|Welcome back!|Sign in' <<<"${texts}"; then
    fail "session: signed out; run ./scripts/sign-in-devs-auto.sh ${serial}"
  elif [[ "${tabs}" -eq 5 ]]; then
    ok "session: signed-in shell on screen"
  else
    fail "session: shell not on screen (another app, the player, or a dialog); bring the app to Files and rerun"
  fi
fi
finish
