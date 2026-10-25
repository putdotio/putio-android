#!/usr/bin/env bash
# Contract tests for sign-in-devs-auto.sh over a fake adb and a fake putio CLI:
# setup failures stop before the device, the dry run precedes the approval, the
# account must match, and no token reaches the output. Touches no device.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

SECRET="fake-ambient-token-value"
ACCOUNT=306077
mkdir -p "${tmpdir}/sdk/platform-tools" "${tmpdir}/cli" "${tmpdir}/nocli" "${tmpdir}/tools"
# The interpreter itself, not a version-manager shim that needs the real HOME.
ln -s "$(python3 -c 'import sys; print(sys.executable)')" "${tmpdir}/tools/python3"

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
    printf '{"command":"auth approve","dryRun":true,"request":%s}\n' "$4" ;;
  "auth approve --json "*" --output json") touch "${FAKE_STATE}/approved"; echo '{}' ;;
  *) echo "unexpected putio call: $*" >&2; exit 2 ;;
esac
EOF

cat > "${tmpdir}/sdk/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "${FAKE_STATE}/adb-calls"
shift 2
node() { printf '<node text="%s" content-desc="" />' "$1"; }
case "$*" in
  get-state) echo device ;;
  "shell pm path io.put.putio.mobile.debug") echo "package:/data/app/base.apk" ;;
  *"am start -W --activity-clear-task -n io.put.putio.mobile.debug/io.putdotio.android.auth.DebugDeviceSignInActivity")
    if [[ -e "${FAKE_STATE}/release-build" ]]; then echo "Error type 3"; else echo "Status: ok"; fi ;;
  *"am start -W -n io.put.putio.mobile.debug/io.putdotio.android.MainActivity") touch "${FAKE_STATE}/main" ;;
  "exec-out uiautomator dump /dev/tty")
    printf '<?xml version="1.0"?><hierarchy>'
    if [[ -e "${FAKE_STATE}/main" ]]; then
      # A closed navigation drawer (large text, compact windows) hides every tab label.
      node "Search your files"
    elif [[ -e "${FAKE_STATE}/app-account" ]]; then
      node "Signed in account $(cat "${FAKE_STATE}/app-account")"
    elif [[ -e "${FAKE_STATE}/approved" ]]; then
      node "Signed in account $(cat "${FAKE_STATE}/approved-account" 2>/dev/null || echo 306077)"
    elif [[ -e "${FAKE_STATE}/expired" ]]; then
      node "Device sign-in stopped: code expired (BUDGET_ELAPSED)"
    else
      node "Activation code ABC123"
    fi
    printf '</hierarchy>UI hierchary dumped to: /dev/tty\n' ;;
  *) echo "unexpected adb call: $*" >&2; exit 2 ;;
esac
EOF
chmod +x "${tmpdir}/cli/putio" "${tmpdir}/sdk/platform-tools/adb"

failures=0
fail() { echo "FAIL ${case_name}: $*" >&2; failures=$((failures + 1)); }

# Runs the script in a fresh state dir; sets $status, $out (stdout) and $err (stderr).
run_case() {
  case_name="$1"; shift
  state="${tmpdir}/state-${case_name}"
  mkdir -p "${state}"
  local flag
  for flag in "$@"; do
    case "${flag}" in
      *=*) printf '%s\n' "${flag#*=}" > "${state}/${flag%%=*}" ;;
      *) touch "${state}/${flag}" ;;
    esac
  done
  local path="${tmpdir}/cli:${tmpdir}/tools:/usr/bin:/bin"
  [[ -e "${state}/no-cli" ]] && path="${tmpdir}/nocli:${tmpdir}/tools:/usr/bin:/bin"
  status=0
  env -i HOME="${tmpdir}" PATH="${path}" ANDROID_HOME="${tmpdir}/sdk" FAKE_STATE="${state}" \
    PUTIO_CLI_TOKEN="${SECRET}" PUTIO_SIGN_IN_TIMEOUT=3 PUTIO_SIGN_IN_POLL=0 \
    bash "${REPO_ROOT}/scripts/sign-in-devs-auto.sh" emulator-5554 \
    > "${state}/out" 2> "${state}/err" || status=$?
  out="$(cat "${state}/out")"
  err="$(cat "${state}/err")"
  if grep -q "${SECRET}" "${state}/out" "${state}/err"; then fail "a token reached the output"; fi
  if [[ -e "${state}/putio-calls" ]] && grep -q 'token=set' "${state}/putio-calls"; then
    fail "putio ran with an ambient PUTIO_CLI_TOKEN"
  fi
}

expect_failure() {
  [[ "${status}" != 0 ]] || fail "expected failure"
  [[ "${err}" == *"$1"* ]] || fail "stderr lacks '$1': ${err}"
  [[ "${out}" != *"PROOF PASS"* ]] || fail "printed PROOF PASS"
}

run_case approves-code
[[ "${status}" == 0 ]] || fail "exit ${status}: ${err}"
[[ "${out}" == $'SIGNED IN '"${ACCOUNT}"$'\nPROOF PASS sign-in-devs-auto' ]] || fail "stdout: ${out}"
approvals="$(grep '^auth approve' "${state}/putio-calls" || true)"
[[ "${approvals}" == $'auth approve --json {"code":"ABC123"} --dry-run --output json token= profile=devs-auto\nauth approve --json {"code":"ABC123"} --output json token= profile=devs-auto' ]] ||
  fail "approvals: ${approvals}"
[[ "${out}${err}" != *ABC123* ]] || fail "the activation code reached the output"

run_case already-signed-in "app-account=${ACCOUNT}"
[[ "${status}" == 0 && "${out}" == *"PROOF PASS"* ]] || fail "exit ${status}: ${err}"
! grep -q '^auth approve' "${state}/putio-calls" || fail "approved with a session already in place"

run_case wrong-account approved-account=42
expect_failure "app is signed in as account 42, not devs-auto (${ACCOUNT})"
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
