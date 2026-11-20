#!/usr/bin/env bash
# Contract tests for sign-in-devs-auto.sh over a fake adb and a fake putio CLI:
# setup failures stop before the device, the dry run precedes the approval, the
# account must match, a cached token replaces the code, and no token reaches the
# output. Touches no device: before each case runs, putio, python3 and the SDK
# root must resolve to the fakes, so a host with putio in /usr/bin or /bin
# fails the suite; in cases with a fake CLI, a putio lookup that misses it hits
# a trap that stops the run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT
if [[ "${tmpdir}" == *:* ]] && command -v cygpath >/dev/null 2>&1; then
  tmpdir="$(cygpath -u "${tmpdir}")"
fi

SECRET="fake-ambient-token-value"
APP_TOKEN="fake-app-token-from-approval"
CACHED_TOKEN="fake-cached-app-token"
STALE_TOKEN="fake-stale-app-token"
ACCOUNT=306077
mkdir -p "${tmpdir}/sdk/platform-tools" "${tmpdir}/cli" "${tmpdir}/nocli" "${tmpdir}/tools" "${tmpdir}/trap"
printf '#!/bin/sh\necho "putio $*" >> %q\nexit 97\n' "${tmpdir}/escaped" > "${tmpdir}/trap/putio"
chmod +x "${tmpdir}/trap/putio"
# The interpreter itself, not a version-manager shim that needs the real HOME.
printf '#!/bin/sh\nexec %q "$@"\n' "$(python3 -c 'import sys; print(sys.executable)')" > "${tmpdir}/tools/python3"
chmod +x "${tmpdir}/tools/python3"

cat > "${tmpdir}/cli/putio" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s token=%s profile=%s\n' "$*" "${PUTIO_CLI_TOKEN:+set}" "${PUTIO_CLI_PROFILE:-}" >> "${FAKE_STATE}/putio-calls"
case "$*" in
  "auth status --profile devs-auto --output json")
    if [[ -e "${FAKE_STATE}/cli-signed-out" ]]; then
      echo '{"authenticated":false,"profile":"devs-auto","source":"none"}'
    else
      echo '{"authenticated":true,"profile":"devs-auto","source":"profile","apiBaseUrl":"https://api.put.io"}'
    fi ;;
  "whoami --fields info --output json") echo '{"info":{"user_id":306077,"username":"devs-auto"}}' ;;
  "auth approve --json "*" --dry-run --output json")
    if [[ -e "${FAKE_STATE}/bad-preview" ]]; then
      echo '{"command":"auth approve","dryRun":true,"request":{"code":"OTHER1"}}'
    else
      printf '{"command":"auth approve","dryRun":true,"request":%s}\n' "$4"
    fi ;;
  "auth approve --json "*" --output json") touch "${FAKE_STATE}/approved"; echo '{}' ;;
  *) echo "unexpected putio call: $*" >&2; exit 2 ;;
esac
EOF

cat > "${tmpdir}/sdk/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "${FAKE_STATE}/adb-calls"
shift 2
node() { printf '<node index="0" text="%s" package="%s" content-desc="" />' "$1" "${2:-io.put.putio.mobile.debug}"; }
case "$*" in
  get-state) echo device ;;
  "shell pm path io.put.putio.mobile.debug") echo "package:/data/app/base.apk" ;;
  "exec-in run-as io.put.putio.mobile.debug sh -c 'mkdir -p files/harness && cat > files/harness/import-token'")
    cat > "${FAKE_STATE}/pushed-token" ;;
  "exec-out run-as io.put.putio.mobile.debug sh -c 'cat files/harness/export-token && rm -f files/harness/export-token'")
    [[ -e "${FAKE_STATE}/approved" && ! -e "${FAKE_STATE}/no-export" ]] && printf 'fake-app-token-from-approval' ;;
  *"am start -W --activity-clear-task --ez io.putdotio.android.harness.EXPORT_TOKEN true -n io.put.putio.mobile.debug/io.putdotio.android.auth.DebugDeviceSignInActivity")
    # The activity consumes a pushed token on launch.
    [[ -e "${FAKE_STATE}/pushed-token" ]] && mv "${FAKE_STATE}/pushed-token" "${FAKE_STATE}/imported-token"
    if [[ -e "${FAKE_STATE}/release-build" ]]; then echo "Error type 3"; else echo "Status: ok"; fi ;;
  *"am start -W -n io.put.putio.mobile.debug/io.putdotio.android.MainActivity") touch "${FAKE_STATE}/main" ;;
  "exec-out uiautomator dump /dev/tty")
    printf '<?xml version="1.0"?><hierarchy>'
    if [[ -e "${FAKE_STATE}/main" ]]; then
      # A closed navigation drawer (large text, compact windows) hides every tab label.
      node "Search your files"
    elif [[ -e "${FAKE_STATE}/app-account" ]]; then
      node "Signed in account $(cat "${FAKE_STATE}/app-account")"
    elif [[ -e "${FAKE_STATE}/imported-token" && "$(cat "${FAKE_STATE}/imported-token")" == fake-stale-app-token ]]; then
      rm "${FAKE_STATE}/imported-token"
      node "Cached token rejected: put.io rejected it"
    elif [[ -e "${FAKE_STATE}/imported-token" ]]; then
      node "Signed in account 306077"
    elif [[ -e "${FAKE_STATE}/approved" ]]; then
      node "Signed in account $(cat "${FAKE_STATE}/approved-account" 2>/dev/null || echo 306077)"
    elif [[ -e "${FAKE_STATE}/expired" ]]; then
      node "Device sign-in stopped: code expired (BUDGET_ELAPSED)"
    else
      # Another app's text comes first in the hierarchy; it must never be approved.
      [[ -e "${FAKE_STATE}/foreign-code" ]] && node "Activation code EVIL99" com.example.other
      node "Activation code ABC123"
    fi
    printf '</hierarchy>UI hierchary dumped to: /dev/tty\n' ;;
  *) echo "unexpected adb call: $*" >&2; exit 2 ;;
esac
EOF
chmod +x "${tmpdir}/cli/putio" "${tmpdir}/sdk/platform-tools/adb"

failures=0
fail() { echo "FAIL ${case_name}: $*" >&2; failures=$((failures + 1)); }

isolation_error() {
  local resolved expected
  resolved="$(env -i HOME="${tmpdir}" PATH="$1" ANDROID_HOME="${3:-${tmpdir}/sdk}" bash -c '
    source "$1/scripts/lib.sh"
    printf "putio=%s python3=%s sdk=%s" "$(command -v putio || true)" "$(command -v python3 || true)" "$(resolve_sdk_root || true)"
  ' _ "${REPO_ROOT}" 2>&1 || true)"
  expected="putio=$2 python3=${tmpdir}/tools/python3 sdk=${tmpdir}/sdk"
  [[ "${resolved}" == "${expected}" ]] || printf 'resolves %s, expected %s' "${resolved}" "${expected}"
}

# Runs the script in a fresh state dir; sets $status, $out (stdout) and $err (stderr).
run_case() {
  case_name="$1"; shift
  state="${tmpdir}/state-${case_name}"
  mkdir -p "${state}"
  local flag
  for flag in "$@"; do
    case "${flag}" in
      cache=*)
        mkdir -p "${tmpdir}/home-${case_name}/.cache/putio-android/devs-auto"
        printf '%s' "${flag#cache=}" > "${tmpdir}/home-${case_name}/.cache/putio-android/devs-auto/io.put.putio.mobile.debug.token" ;;
      *=*) printf '%s\n' "${flag#*=}" > "${state}/${flag%%=*}" ;;
      *) touch "${state}/${flag}" ;;
    esac
  done
  local cli="${tmpdir}/cli" putio="${tmpdir}/cli/putio"
  [[ -e "${state}/no-cli" ]] && cli="${tmpdir}/nocli" putio=""
  local path="${cli}:${tmpdir}/tools:${tmpdir}/trap:/usr/bin:/bin" isolation
  [[ -z "${putio}" ]] && path="${cli}:${tmpdir}/tools:/usr/bin:/bin"
  isolation="$(isolation_error "${path}" "${putio}")"
  if [[ -n "${isolation}" ]]; then
    fail "not isolated from real tools, so no case ran: ${isolation}"
    exit 1
  fi
  status=0
  home="${tmpdir}/home-${case_name}"
  cache="${home}/.cache/putio-android/devs-auto/io.put.putio.mobile.debug.token"
  mkdir -p "${home}"
  env -i HOME="${home}" PATH="${path}" ANDROID_HOME="${tmpdir}/sdk" FAKE_STATE="${state}" \
    PUTIO_CLI_TOKEN="${SECRET}" PUTIO_SIGN_IN_TIMEOUT=3 PUTIO_SIGN_IN_POLL=0 \
    bash "${REPO_ROOT}/scripts/sign-in-devs-auto.sh" emulator-5554 \
    > "${state}/out" 2> "${state}/err" || status=$?
  if [[ -s "${tmpdir}/escaped" ]]; then
    fail "called a tool outside its fakes, so the run stopped: $(cat "${tmpdir}/escaped")"
    exit 1
  fi
  out="$(cat "${state}/out")"
  err="$(cat "${state}/err")"
  if grep -qE "${SECRET}|fake-[a-z-]*app-token" "${state}/out" "${state}/err" "${state}/adb-calls" 2>/dev/null; then
    fail "a token reached the output or an adb argument"
  fi
  if [[ -e "${state}/putio-calls" ]] && grep -q 'token=set' "${state}/putio-calls"; then
    fail "putio ran with an ambient PUTIO_CLI_TOKEN"
  fi
}

expect_failure() {
  [[ "${status}" != 0 ]] || fail "expected failure"
  [[ "${err}" == *"$1"* ]] || fail "stderr lacks '$1': ${err}"
  [[ "${out}" != *"PROOF PASS"* ]] || fail "printed PROOF PASS"
}

case_name="isolation-guard"
[[ -n "$(isolation_error "${tmpdir}/nocli:${tmpdir}/tools:${tmpdir}/trap:/usr/bin:/bin" "${tmpdir}/cli/putio")" ]] ||
  fail "accepted a PATH whose putio is not the fake"
[[ -n "$(isolation_error "${tmpdir}/cli:${tmpdir}/tools:/usr/bin:/bin" "")" ]] ||
  fail "accepted a PATH with a putio although the case expects none"
[[ -n "$(isolation_error "${tmpdir}/cli:${tmpdir}/nocli:/usr/bin:/bin" "${tmpdir}/cli/putio")" ]] ||
  fail "accepted a PATH whose python3 is not the shim"
[[ -n "$(isolation_error "${tmpdir}/cli:${tmpdir}/tools:/usr/bin:/bin" "${tmpdir}/cli/putio" "${tmpdir}/missing-sdk")" ]] ||
  fail "accepted an SDK root other than the fake"
[[ -z "$(isolation_error "${tmpdir}/cli:${tmpdir}/tools:/usr/bin:/bin" "${tmpdir}/cli/putio")" ]] ||
  fail "rejected the fakes: $(isolation_error "${tmpdir}/cli:${tmpdir}/tools:/usr/bin:/bin" "${tmpdir}/cli/putio")"
((failures == 0)) || exit 1

case_name="escape-tripwire"
env -i HOME="${tmpdir}" PATH="${tmpdir}/nocli:${tmpdir}/tools:${tmpdir}/trap:/usr/bin:/bin" \
  ANDROID_HOME="${tmpdir}/sdk" PUTIO_SIGN_IN_TIMEOUT=3 PUTIO_SIGN_IN_POLL=0 \
  bash "${REPO_ROOT}/scripts/sign-in-devs-auto.sh" emulator-5554 >/dev/null 2>&1 || true
[[ -s "${tmpdir}/escaped" ]] ||
  fail "a putio outside the fakes went unnoticed"
rm -f "${tmpdir}/escaped"
((failures == 0)) || exit 1

run_case approves-code
[[ "${status}" == 0 ]] || fail "exit ${status}: ${err}"
[[ "${out}" == $'SIGNED IN '"${ACCOUNT}"$'\nPROOF PASS sign-in-devs-auto' ]] || fail "stdout: ${out}"
approvals="$(grep '^auth approve' "${state}/putio-calls" || true)"
[[ "${approvals}" == $'auth approve --json {"code":"ABC123"} --dry-run --output json token= profile=devs-auto\nauth approve --json {"code":"ABC123"} --output json token= profile=devs-auto' ]] ||
  fail "approvals: ${approvals}"
[[ "${out}${err}" != *ABC123* ]] || fail "the activation code reached the output"
[[ "$(cat "${cache}" 2>/dev/null)" == "${APP_TOKEN}" ]] || fail "the approved app's token was not cached"
[[ "$(stat -c %a "${cache}" 2>/dev/null || stat -f %Lp "${cache}")" == 600 ]] || fail "the cached token is not 0600"

run_case uses-cached-token "cache=${CACHED_TOKEN}"
[[ "${status}" == 0 && "${out}" == *"PROOF PASS"* ]] || fail "exit ${status}: ${err}"
[[ "$(cat "${state}/imported-token" 2>/dev/null)" == "${CACHED_TOKEN}" ]] || fail "the cached token was not pushed"
[[ ! -e "${state}/putio-calls" ]] || ! grep -q '^auth approve' "${state}/putio-calls" ||
  fail "approved a code although a cached token signed in"

run_case stale-cached-token "cache=${STALE_TOKEN}"
[[ "${status}" == 0 && "${out}" == *"PROOF PASS"* ]] || fail "exit ${status}: ${err}"
[[ "$(grep -c '^auth approve' "${state}/putio-calls")" == 2 ]] || fail "expected one dry run and one approval"
[[ "$(cat "${cache}" 2>/dev/null)" == "${APP_TOKEN}" ]] || fail "the rejected token was not replaced"

run_case no-export no-export
[[ "${status}" == 0 && "${out}" == *"PROOF PASS"* ]] || fail "exit ${status}: ${err}"
[[ ! -e "${cache}" ]] || fail "cached an empty token"

run_case already-signed-in "app-account=${ACCOUNT}"
[[ "${status}" == 0 && "${out}" == *"PROOF PASS"* ]] || fail "exit ${status}: ${err}"
! grep -q '^auth approve' "${state}/putio-calls" || fail "approved with a session already in place"

run_case wrong-account approved-account=42
expect_failure "app is signed in as account 42, not devs-auto (${ACCOUNT})"
[[ ! -e "${cache}" ]] || fail "cached another account's token"
[[ ! -e "${state}/main" ]] || fail "opened Files for the wrong account"

run_case other-session "app-account=42"
expect_failure "app is signed in as account 42"

run_case cli-signed-out cli-signed-out
expect_failure "putio auth login --profile devs-auto"
[[ ! -e "${state}/adb-calls" ]] || fail "touched the device before the CLI check"

run_case no-cli no-cli
expect_failure "putio CLI not on PATH"

run_case release-build release-build
expect_failure "has no device-code sign-in"

run_case foreign-code foreign-code
[[ "${status}" == 0 ]] || fail "exit ${status}: ${err}"
! grep -q 'EVIL99' "${state}/putio-calls" || fail "approved another app's code"

run_case bad-preview bad-preview
expect_failure "dry run did not preview this code"
[[ "$(grep -c '^auth approve' "${state}/putio-calls")" == 1 ]] || fail "approved after a mismatched preview"

run_case code-expired expired
expect_failure "Device sign-in stopped: code expired"
! grep -q '^auth approve' "${state}/putio-calls" || fail "approved after the code stopped"

case_name=help
help="$(env -i PATH=/usr/bin:/bin bash "${REPO_ROOT}/scripts/sign-in-devs-auto.sh" --help)" || fail "--help failed"
[[ "${help}" == "Usage: scripts/sign-in-devs-auto.sh <serial> [package]"* && "${help}" == *Prerequisites:* ]] ||
  fail "--help: ${help}"

if ((failures > 0)); then
  echo "sign-in contract: ${failures} failure(s)" >&2
  exit 1
fi
echo "sign-in contract: all cases passed"
