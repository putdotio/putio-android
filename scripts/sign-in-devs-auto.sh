#!/usr/bin/env bash
# Signs the mobile debug app in as devs-auto through its device-code screen and
# the putio CLI. `scripts/sign-in-devs-auto.sh --help` documents it.

usage() {
  cat <<'EOF'
Usage: scripts/sign-in-devs-auto.sh <serial> [package]

Signs the mobile debug app in as the shared test identity devs-auto, with no
browser and no password. The debug-only device-code screen
(io.putdotio.android.auth.DebugDeviceSignInActivity, absent from release
builds) shows a code; the putio CLI approves it after a dry run. The session is
the app's normal Keystore-backed one, as after a browser sign-in.

  serial    a running device, e.g. emulator-5554
  package   default io.put.putio.mobile.debug (the production debug build)

Prerequisites:
  - putio CLI on PATH, signed in: putio auth login --profile devs-auto
  - the device running, e.g. scripts/emulator.sh boot phone --headless
  - a debug build of this revision installed:
    ./gradlew :mobile:assembleProductionDebug
    adb -s <serial> install -r mobile/build/outputs/apk/production/debug/mobile-production-debug.apk

Steps: check the CLI profile and its account ID, open the device-code screen,
read the code with uiautomator, approve it with
PUTIO_CLI_PROFILE=devs-auto putio auth approve (dry run first), wait for the
app's account ID and compare it with the CLI's, then open MainActivity and
wait for Files. An app already signed in as devs-auto passes with no approval.

Stdout: SIGNED IN <account id>, then PROOF PASS sign-in-devs-auto.
Stderr: progress, and on failure one ERROR line naming the fix:
  - putio missing, its profile signed out, or not on https://api.put.io
  - device not running, or the package not installed
  - no device-code screen: a release build, or a debug build older than it
  - "Device sign-in stopped: <reason>": the app's code expired or put.io failed
  - app signed in as another account: sign out or pm clear the package first
  - a timeout waiting for the code, the account, or Files

No token enters an argument, the log or the output; an ambient
PUTIO_CLI_TOKEN is ignored. Each live approval creates a grant for the app on
devs-auto; signing out in the app revokes it.

Environment: PUTIO_SIGN_IN_TIMEOUT bounds each wait in seconds (default 90);
PUTIO_SIGN_IN_POLL is the pause between UI reads (default 2).
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PROFILE="devs-auto"
API_BASE_URL="https://api.put.io"
SIGN_IN_ACTIVITY="io.putdotio.android.auth.DebugDeviceSignInActivity"
MAIN_ACTIVITY="io.putdotio.android.MainActivity"
TIMEOUT="${PUTIO_SIGN_IN_TIMEOUT:-90}"
POLL="${PUTIO_SIGN_IN_POLL:-2}"

serial="${1:-}"
package="${2:-io.put.putio.mobile.debug}"
[[ -n "${serial}" && $# -le 2 ]] || die "usage: scripts/sign-in-devs-auto.sh <serial> [package]; see --help"
[[ "${package}" =~ ^[A-Za-z0-9_.]+$ ]] || die "invalid package name: ${package}"

# The profile alone decides the identity; an ambient token would override it.
cli() { env -u PUTIO_CLI_TOKEN PUTIO_CLI_PROFILE="${PROFILE}" putio "$@"; }

# Prints the value at a dotted path of the JSON on stdin; empty when absent.
json_path() {
  python3 -I -c '
import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    value = value.get(key) if isinstance(value, dict) else None
print("" if value is None else json.dumps(value) if isinstance(value, bool) else value)
' "$1"
}

command -v putio >/dev/null 2>&1 ||
  die "putio CLI not on PATH; install it (https://github.com/putdotio/putio-cli), then run: putio auth login --profile ${PROFILE}"

status="$(env -u PUTIO_CLI_TOKEN putio auth status --profile "${PROFILE}" --output json 2>/dev/null)" ||
  die "putio auth status failed; run: putio auth login --profile ${PROFILE}"
[[ "$(json_path authenticated <<<"${status}")" == "true" &&
  "$(json_path profile <<<"${status}")" == "${PROFILE}" &&
  "$(json_path source <<<"${status}")" == "profile" ]] ||
  die "putio profile ${PROFILE} is not signed in; run: putio auth login --profile ${PROFILE}"
[[ "$(json_path apiBaseUrl <<<"${status}")" == "${API_BASE_URL}" ]] ||
  die "putio profile ${PROFILE} must use ${API_BASE_URL}"

whoami="$(cli whoami --fields info --output json 2>/dev/null)" || die "putio whoami failed for ${PROFILE}"
account_id="$(json_path info.user_id <<<"${whoami}")"
[[ "${account_id}" =~ ^[1-9][0-9]*$ && "$(json_path info.username <<<"${whoami}")" == "${PROFILE}" ]] ||
  die "putio profile ${PROFILE} does not resolve to the ${PROFILE} account"
log "CLI profile ${PROFILE} is account ${account_id}"

require_sdk_root
adb_() { "${ADB}" -s "${serial}" "$@"; }
[[ "$(adb_ get-state 2>/dev/null)" == "device" ]] ||
  die "${serial} is not a running device; boot one with scripts/emulator.sh boot phone --headless"
adb_ shell pm path "${package}" 2>/dev/null | grep -q '^package:' ||
  die "${package} is not installed on ${serial}; run ./gradlew :mobile:assembleProductionDebug, then adb -s ${serial} install -r mobile/build/outputs/apk/production/debug/mobile-production-debug.apk"

# Visible text and content descriptions, one per line.
screen_texts() {
  adb_ exec-out uiautomator dump /dev/tty 2>/dev/null |
    grep -oE '(text|content-desc)="[^"]*"' | sed -E 's/^[a-z-]+="(.*)"$/\1/' || true
}

# Waits until a screen line matches $1 (an ERE); prints the first match. A line
# matching "Device sign-in stopped" ends the wait with the app's reason.
await_text() {
  local pattern="$1" what="$2" deadline=$((SECONDS + TIMEOUT)) texts match
  while ((SECONDS < deadline)); do
    texts="$(screen_texts)"
    match="$(grep -m1 -E "${pattern}" <<<"${texts}" || true)"
    [[ -n "${match}" ]] && { printf '%s\n' "${match}"; return 0; }
    match="$(grep -m1 -E '^Device sign-in stopped: ' <<<"${texts}" || true)"
    [[ -n "${match}" ]] && die "${match}"
    sleep "${POLL}"
  done
  die "timed out after ${TIMEOUT}s waiting for ${what} on ${serial}"
}

started="$(adb_ shell am start -W --activity-clear-task -n "${package}/${SIGN_IN_ACTIVITY}" 2>&1 || true)"
[[ "${started}" != *Error* && "${started}" != *Exception* ]] ||
  die "${package} has no device-code sign-in; install a debug build of this revision"

first="$(await_text '^(Activation code [A-Z0-9]+|Signed in account [0-9]+)$' "an activation code")"
if [[ "${first}" == Activation\ code\ * ]]; then
  code="${first#Activation code }"
  payload="{\"code\":\"${code}\"}"
  preview="$(cli auth approve --json "${payload}" --dry-run --output json 2>/dev/null)" ||
    die "putio auth approve --dry-run failed"
  [[ "$(json_path command <<<"${preview}")" == "auth approve" &&
    "$(json_path dryRun <<<"${preview}")" == "true" &&
    "$(json_path request.code <<<"${preview}")" == "${code}" ]] ||
    die "putio auth approve dry run did not preview this code"
  cli auth approve --json "${payload}" --output json >/dev/null 2>&1 || die "putio auth approve failed"
  log "approved the app's code as ${PROFILE}"
  first="$(await_text '^Signed in account [0-9]+$' "the signed-in account")"
else
  log "app already signed in; no approval needed"
fi

app_account="${first#Signed in account }"
[[ "${app_account}" == "${account_id}" ]] ||
  die "app is signed in as account ${app_account}, not ${PROFILE} (${account_id}); sign out or pm clear ${package} first"
echo "SIGNED IN ${app_account}"

adb_ shell am start -W -n "${package}/${MAIN_ACTIVITY}" >/dev/null 2>&1 || die "could not open ${MAIN_ACTIVITY}"
await_text '^Transfers$' "Files" >/dev/null
await_text '^Files$' "Files" >/dev/null
echo "PROOF PASS sign-in-devs-auto"
