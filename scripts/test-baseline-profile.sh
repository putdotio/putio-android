#!/usr/bin/env bash
# Contract tests for generate-baseline-profile.sh over a fake adb, gradlew and
# putio CLI: tv (default and explicit) and mobile invocations succeed after one
# approval bound to the instrumentation PID (ignoring other PIDs); a failed
# generator exits 1; an invalid target exits 64.
# Touches no device.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

mkdir -p "${tmpdir}/sdk/platform-tools" "${tmpdir}/cli" "${tmpdir}/tools"
printf '#!/bin/sh\nexec %q "$@"\n' "$(python3 -c 'import sys; print(sys.executable)')" > "${tmpdir}/tools/python3"

cat > "${tmpdir}/cli/putio" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s token=%s profile=%s\n' "$*" "${PUTIO_CLI_TOKEN:+set}" "${PUTIO_CLI_PROFILE:-}" >> "${FAKE_STATE}/putio-calls"
case "$*" in
  "auth status --profile devs-auto --output json") echo '{"authenticated":true,"profile":"devs-auto"}' ;;
  "whoami --fields info --output json") echo '{"info":{"user_id":306077,"username":"devs-auto"}}' ;;
  "auth approve --json "*" --dry-run --output json") printf '{"dryRun":true,"request":%s}\n' "$4" ;;
  "auth approve --json "*" --output json")
    printf '%s\n' "$4" >> "${FAKE_STATE}/approved-payloads"
    touch "${FAKE_STATE}/approved"
    echo '{}'
    ;;
  *) echo "unexpected putio call: $*" >&2; exit 2 ;;
esac
EOF

cat > "${tmpdir}/sdk/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
shift 2
case "$*" in
  get-state) echo device ;;
  "shell getprop ro.build.version.sdk") echo 36 ;;
  "shell pidof io.putdotio.android.baselineprofile.tv"|"shell pidof io.putdotio.android.baselineprofile.mobile")
    printf '%s\n' "$*" > "${FAKE_STATE}/pidof-call"
    echo 4242
    ;;
  "logcat -c") ;;
  "logcat -d --pid="*' -s PutioBaselineProfile:I')
    pid="${3#--pid=}"
    awk -v pid="${pid}" '$3 == pid' "${FAKE_STATE}/logcat" 2>/dev/null || true
    ;;
  *) echo "unexpected adb call: $*" >&2; exit 2 ;;
esac
EOF

cat > "${tmpdir}/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" > "${FAKE_STATE}/gradle-args"
line() { printf '11-14 10:00:%02d.000  %s  %s I PutioBaselineProfile: %s\n' "$1" "$2" "$2" "$3" >> "${FAKE_STATE}/logcat"; }
line 1 4242 "activation code ABC123"
line 2 9999 "activation code SPOOF9"
for _ in $(seq 300); do [[ -e "${FAKE_STATE}/approved" ]] && break; sleep 0.1; done
[[ -e "${FAKE_STATE}/approved" ]] || { echo "the code was never approved"; exit 1; }
echo "fake generator output"
exit "$(cat "${FAKE_STATE}/gradle-exit" 2>/dev/null || echo 0)"
EOF
chmod +x "${tmpdir}/tools/python3" "${tmpdir}/cli/putio" "${tmpdir}/sdk/platform-tools/adb" "${tmpdir}/gradlew"

failures=0
fail() { echo "FAIL ${case_name}: $*" >&2; failures=$((failures + 1)); }

run_case() {
  case_name="$1"; shift
  state="${tmpdir}/state-${case_name}"
  mkdir -p "${state}"
  cp "${tmpdir}/gradlew" "${state}/gradlew"
  local args=() flag
  while [[ $# -gt 0 ]]; do
    flag="$1"; shift
    if [[ "${flag}" == "--" ]]; then
      args=("$@")
      break
    fi
    case "${flag}" in
      *=*) printf '%s\n' "${flag#*=}" > "${state}/${flag%%=*}" ;;
      *) touch "${state}/${flag}" ;;
    esac
  done
  if [[ ${#args[@]} -eq 0 ]]; then
    args=(emulator-5554)
  fi
  status=0
  (cd "${state}" && env -i HOME="${state}" PATH="${tmpdir}/cli:${tmpdir}/tools:/usr/bin:/bin" \
    ANDROID_HOME="${tmpdir}/sdk" FAKE_STATE="${state}" PUTIO_CLI_TOKEN=fake-ambient-token \
    bash "${REPO_ROOT}/scripts/generate-baseline-profile.sh" "${args[@]}") 2> "${state}/err" || status=$?
  err="$(cat "${state}/err")"
  ! grep -q 'token=set' "${state}/putio-calls" 2>/dev/null || fail "putio ran with an ambient PUTIO_CLI_TOKEN"
  ! grep -q 'SPOOF9' "${state}/approved-payloads" 2>/dev/null || fail "approved a code from another PID"
}

run_case generates
[[ "${status}" == 0 ]] || fail "exit ${status}: ${err}"
[[ "${err}" == *"wrote tv/src/main/generated/baselineProfiles/"* ]] || fail "stderr: ${err}"
[[ "$(cat "${state}/gradle-args")" == *"-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :tv:generateBaselineProfile"* ]] || fail "gradle args: $(cat "${state}/gradle-args")"
[[ "$(cat "${state}/pidof-call")" == "shell pidof io.putdotio.android.baselineprofile.tv" ]] || fail "pidof: $(cat "${state}/pidof-call")"
[[ "$(cat "${state}/approved-payloads")" == '{"code":"ABC123"}' ]] || fail "approved payloads: $(cat "${state}/approved-payloads")"
[[ "$(grep -c '^auth approve.*profile=devs-auto$' "${state}/putio-calls")" == 2 ]] || fail "expected one dry run and one approval as devs-auto"

run_case generates-tv -- tv emulator-5554
[[ "${status}" == 0 ]] || fail "exit ${status}: ${err}"
[[ "${err}" == *"wrote tv/src/main/generated/baselineProfiles/"* ]] || fail "stderr: ${err}"
[[ "$(cat "${state}/gradle-args")" == *"-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :tv:generateBaselineProfile"* ]] || fail "gradle args: $(cat "${state}/gradle-args")"

run_case generates-mobile -- mobile emulator-5554
[[ "${status}" == 0 ]] || fail "exit ${status}: ${err}"
[[ "${err}" == *"wrote mobile/src/main/generated/baselineProfiles/"* ]] || fail "stderr: ${err}"
[[ "$(cat "${state}/gradle-args")" == *"-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :mobile:generateBaselineProfile"* ]] || fail "gradle args: $(cat "${state}/gradle-args")"
[[ "$(cat "${state}/pidof-call")" == "shell pidof io.putdotio.android.baselineprofile.mobile" ]] || fail "pidof: $(cat "${state}/pidof-call")"
[[ "$(cat "${state}/approved-payloads")" == '{"code":"ABC123"}' ]] || fail "approved payloads: $(cat "${state}/approved-payloads")"
[[ "$(grep -c '^auth approve.*profile=devs-auto$' "${state}/putio-calls")" == 2 ]] || fail "expected one dry run and one approval as devs-auto"

run_case generator-fails gradle-exit=1
[[ "${status}" == 1 ]] || fail "exit ${status}"
[[ "${err}" == *"fake generator output"*"ERROR: profile generation failed"* ]] || fail "stderr: ${err}"

run_case bad-target -- desktop emulator-5554
[[ "${status}" == 64 ]] || fail "exit ${status}"

if ((failures > 0)); then
  echo "baseline profile contract: ${failures} failure(s)" >&2
  exit 1
fi
echo "baseline profile contract: ok"
