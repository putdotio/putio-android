#!/usr/bin/env bash
# Contract tests for generate-baseline-profile.sh over a fake adb, gradlew and
# putio CLI: a run whose generator succeeds and signs out twice exits 0; a
# failed generator or a missing sign-out exits 1.
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
echo "$*" >> "${FAKE_STATE}/putio-calls"
case "$*" in
  "auth status --profile devs-auto --output json") echo '{"authenticated":true,"profile":"devs-auto"}' ;;
  "whoami --fields info --output json") echo '{"info":{"user_id":306077,"username":"devs-auto"}}' ;;
  "auth approve --json "*" --dry-run --output json") printf '{"dryRun":true,"request":%s}\n' "$4" ;;
  "auth approve --json "*" --output json") touch "${FAKE_STATE}/approved"; echo '{}' ;;
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
  "logcat -c") ;;
  "logcat -d -s PutioBaselineProfile:I") cat "${FAKE_STATE}/logcat" 2>/dev/null || true ;;
  *) echo "unexpected adb call: $*" >&2; exit 2 ;;
esac
EOF

cat > "${tmpdir}/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
line() { printf '11-14 10:00:%02d.000  4242  4242 I PutioBaselineProfile: %s\n' "$1" "$2" >> "${FAKE_STATE}/logcat"; }
line 1 "activation code ABC123"
for _ in $(seq 50); do [[ -e "${FAKE_STATE}/approved" ]] && break; sleep 0.1; done
[[ -e "${FAKE_STATE}/approved" ]] || { echo "the code was never approved"; exit 1; }
line 2 "signed out"
[[ -e "${FAKE_STATE}/one-sign-out" ]] || line 3 "signed out"
echo "fake generator output"
exit "$(cat "${FAKE_STATE}/gradle-exit" 2>/dev/null || echo 0)"
EOF
chmod +x "${tmpdir}/tools/python3" "${tmpdir}/cli/putio" "${tmpdir}/sdk/platform-tools/adb" "${tmpdir}/gradlew"

failures=0
fail() { echo "FAIL ${case_name}: $*" >&2; failures=$((failures + 1)); }

# Runs the script from a fresh state dir; sets $status and $err (stderr).
run_case() {
  case_name="$1"; shift
  state="${tmpdir}/state-${case_name}"
  mkdir -p "${state}"
  cp "${tmpdir}/gradlew" "${state}/gradlew"
  local flag
  for flag in "$@"; do
    case "${flag}" in
      *=*) printf '%s\n' "${flag#*=}" > "${state}/${flag%%=*}" ;;
      *) touch "${state}/${flag}" ;;
    esac
  done
  status=0
  (cd "${state}" && env -i HOME="${state}" PATH="${tmpdir}/cli:${tmpdir}/tools:/usr/bin:/bin" \
    ANDROID_HOME="${tmpdir}/sdk" FAKE_STATE="${state}" \
    bash "${REPO_ROOT}/scripts/generate-baseline-profile.sh" emulator-5554) 2> "${state}/err" || status=$?
  err="$(cat "${state}/err")"
}

run_case generates
[[ "${status}" == 0 ]] || fail "exit ${status}: ${err}"
[[ "${err}" == *"wrote tv/src/main/generated/baselineProfiles/"* ]] || fail "stderr: ${err}"
[[ "$(grep -c '^auth approve' "${state}/putio-calls")" == 2 ]] || fail "expected one dry run and one approval"

run_case generator-fails gradle-exit=1
[[ "${status}" == 1 ]] || fail "exit ${status}"
[[ "${err}" == *"fake generator output"*"ERROR: profile generation failed"* ]] || fail "stderr: ${err}"

run_case one-sign-out one-sign-out
[[ "${status}" == 1 ]] || fail "exit ${status}"
[[ "${err}" == *"ERROR: a journey did not sign out"* ]] || fail "stderr: ${err}"

if ((failures > 0)); then
  echo "baseline profile contract: ${failures} failure(s)" >&2
  exit 1
fi
echo "baseline profile contract: ok"
