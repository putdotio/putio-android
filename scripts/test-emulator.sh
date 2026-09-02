#!/usr/bin/env bash
# Fast, isolated contract tests for AVD provisioning and phone readiness.
# Uses a fake SDK and disposable AVD state; never touches a registered AVD.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmpdir="$(mktemp -d)"
fake_sdk="${tmpdir}/sdk"
state="${tmpdir}/state"
is_owned_fake_emulator() {
  local pid="$1" command
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  kill -0 "${pid}" 2>/dev/null || return 1
  command="$(ps -ww -p "${pid}" -o command= 2>/dev/null || true)"
  [[ "${command}" == "/usr/bin/env bash ${fake_sdk}/emulator/emulator "* ||
    "${command}" == "/usr/bin/bash ${fake_sdk}/emulator/emulator "* ||
    "${command}" == "/bin/bash ${fake_sdk}/emulator/emulator "* ||
    "${command}" == "bash ${fake_sdk}/emulator/emulator "* ||
    "${command}" == "${fake_sdk}/emulator/emulator "* ]]
}
cleanup() {
  local pid attempt cleanup_failed=0
  while read -r pid; do
    [[ "${pid}" =~ ^[0-9]+$ ]] || continue
    is_owned_fake_emulator "${pid}" || continue
    kill "${pid}" 2>/dev/null || true
    for attempt in {1..50}; do
      kill -0 "${pid}" 2>/dev/null || break
      sleep 0.02
    done
    if is_owned_fake_emulator "${pid}"; then
      kill -KILL "${pid}" 2>/dev/null || true
      for attempt in {1..50}; do
        kill -0 "${pid}" 2>/dev/null || break
        sleep 0.02
      done
    fi
    if is_owned_fake_emulator "${pid}"; then
      echo "emulator contract cleanup failed: owned fake emulator ${pid} survived" >&2
      cleanup_failed=1
    fi
  done < "${state}/emulator-pids" 2>/dev/null || true
  if [[ "${cleanup_failed}" == "1" ]]; then
    echo "preserving failed cleanup state at ${tmpdir}" >&2
    return 1
  fi
  rm -rf "${tmpdir}"
}
finish() {
  local code=$?
  trap - EXIT
  if ! cleanup; then
    exit 1
  fi
  exit "${code}"
}
trap finish EXIT
trap 'trap - INT TERM; exit 130' INT
trap 'trap - INT TERM; exit 143' TERM

mkdir -p \
  "${fake_sdk}/platform-tools" \
  "${fake_sdk}/emulator" \
  "${fake_sdk}/cmdline-tools/latest/bin" \
  "${state}/avds"
export ANDROID_HOME="${fake_sdk}"
export FAKE_STATE_DIR="${state}"

cat > "${fake_sdk}/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
state="${FAKE_STATE_DIR:?}"
printf '%s\n' "$*" >> "${state}/adb-calls"

if [[ "$*" == "devices" ]]; then
  echo "List of devices attached"
  case "$(<"${state}/running")" in
    1) printf 'emulator-5554\tdevice\n' ;;
    offline) printf 'emulator-5554\toffline\n' ;;
  esac
  exit 0
fi

case "$*" in
  "start-server") ;;
  *" emu avd name")
    [[ "$(<"${state}/running")" == "1" ]] || exit 1
    cat "${state}/name"
    ;;
  *" emu kill")
    echo 0 > "${state}/running"
    pid="$(cat "${state}/emulator-pid" 2>/dev/null || true)"
    [[ "${pid}" =~ ^[0-9]+$ ]] && kill "${pid}" 2>/dev/null || true
    ;;
  *" settings put global hide_error_dialogs 1") ;;
  *" getprop sys.boot_completed") echo 1 ;;
  *" getprop ro.build.version.sdk")
    [[ "$(<"${state}/api-query-succeeds")" == "1" ]] || exit 1
    cat "${state}/api-level"
    ;;
  *" pm path android") echo "package:/system/framework/framework-res.apk" ;;
  *" pm path --user 0 com.android.chrome")
    [[ "$(<"${state}/chrome-present")" == "1" ]] || exit 1
    echo "package:/system/product/app/Chrome/Chrome.apk"
    ;;
  *" cmd role add-role-holder --user 0 android.app.role.BROWSER com.android.chrome")
    [[ "$(<"${state}/role-add-succeeds")" == "1" ]]
    ;;
  *" cmd role get-role-holders --user 0 android.app.role.BROWSER")
    [[ "$(<"${state}/role-query-succeeds")" == "1" ]] || exit 1
    cat "${state}/role-holder"
    ;;
  *" cmd package query-services -a android.support.customtabs.action.CustomTabsService -c androidx.browser.auth.category.AuthTab")
    [[ "$(<"${state}/auth-tab-query-succeeds")" == "1" ]] || exit 1
    auth_tab_package="$(<"${state}/auth-tab-package")"
    [[ -z "${auth_tab_package}" ]] || printf '      packageName=%s\n' "${auth_tab_package}"
    ;;
  *" dumpsys package com.android.chrome")
    [[ "$(<"${state}/version-query-succeeds")" == "1" ]] || exit 1
    echo "    versionName=145.0.0"
    ;;
  *) echo "unexpected fake adb call: $*" >&2; exit 1 ;;
esac
EOF

cat > "${fake_sdk}/cmdline-tools/latest/bin/avdmanager" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
state="${FAKE_STATE_DIR:?}"
avds="${state}/avds"

if [[ "${1:-} ${2:-}" == "list avd" ]]; then
  shopt -s nullglob
  entries=("${avds}"/*)
  if [[ "${3:-}" == "-c" ]]; then
    for entry in "${entries[@]}"; do
      [[ -f "${entry}/config.ini" ]] && basename "${entry}"
    done
  else
    for entry in "${entries[@]}"; do
      printf '    Name: %s\n    Path: %s\n' "$(basename "${entry}")" "${entry}"
    done
  fi
  exit 0
fi

if [[ "${1:-} ${2:-}" == "delete avd" ]]; then
  name="${4:?}"
  printf 'delete:%s\n' "${name}" >> "${state}/avd-operations"
  rm -rf "${avds:?}/${name}"
  exit 0
fi

if [[ "${1:-} ${2:-}" == "create avd" ]]; then
  shift 2
  name=""
  image=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --name) name="${2:?}"; shift ;;
      --package) image="${2:?}"; shift ;;
      --device) shift ;;
      *) echo "unexpected avdmanager argument: $1" >&2; exit 1 ;;
    esac
    shift
  done
  printf 'create:%s:%s\n' "${name}" "${image}" >> "${state}/avd-operations"
  mkdir -p "${avds}/${name}"
  printf '%s\n' "${name}" > "${state}/name"
  registered_image="$(cat "${state}/create-image-override" 2>/dev/null || true)"
  [[ -n "${registered_image}" ]] || registered_image="${image}"
  printf 'image.sysdir.1=%s/\n' "${registered_image//;/\/}" > "${avds}/${name}/config.ini"
  exit 0
fi

echo "unexpected fake avdmanager call: $*" >&2
exit 1
EOF

cat > "${fake_sdk}/emulator/emulator" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
state="${FAKE_STATE_DIR:?}"
name=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -avd) name="${2:?}"; shift ;;
  esac
  shift
done
printf '%s\n' "$$" >> "${state}/emulator-pids"
printf '%s\n' "${name}" > "${state}/name"
printf '%s\n' "$$" > "${state}/emulator-pid"
echo 1 > "${state}/running"
stop() { echo 0 > "${state}/running"; rm -f "${state}/emulator-pid"; exit 0; }
trap stop INT TERM
while true; do sleep 1; done
EOF

chmod +x \
  "${fake_sdk}/platform-tools/adb" \
  "${fake_sdk}/cmdline-tools/latest/bin/avdmanager" \
  "${fake_sdk}/emulator/emulator"

fail() {
  echo "emulator contract test failed: $*" >&2
  exit 1
}

# shellcheck source=lib.sh
source "${REPO_ROOT}/scripts/lib.sh"
require_sdk_root
target_image="system-images;android-37.0;google_apis_playstore;$(sdk_arch)"
[[ "$(phone_image)" == "${target_image}" ]] || fail "phone image is not pinned to API 37 Google Play"
mkdir -p "${fake_sdk}/$(tr ';' '/' <<<"${target_image}")"

reset_avd() {
  local name="$1" image="$2" running="$3"
  rm -rf "${state}/avds"
  mkdir -p "${state}/avds/${name}"
  printf '%s\n' "${name}" > "${state}/name"
  printf '%s\n' "${running}" > "${state}/running"
  printf 'image.sysdir.1=%s/\n' "${image//;/\/}" > "${state}/avds/${name}/config.ini"
  : > "${state}/avd-operations"
  : > "${state}/adb-calls"
}

reset_runtime() {
  printf '%s\n' "${1:-37}" > "${state}/api-level"
  printf '%s\n' "${2:-1}" > "${state}/chrome-present"
  printf '%s\n' "${3:-1}" > "${state}/role-add-succeeds"
  printf '%s\n' "${4:-com.android.chrome}" > "${state}/role-holder"
  printf '%s\n' "${5-com.android.chrome}" > "${state}/auth-tab-package"
  printf '1\n' > "${state}/api-query-succeeds"
  printf '1\n' > "${state}/role-query-succeeds"
  printf '1\n' > "${state}/auth-tab-query-succeeds"
  printf '1\n' > "${state}/version-query-succeeds"
  : > "${state}/adb-calls"
}

legacy_image="system-images;android-36;google_apis;$(sdk_arch)"
non_play_image="system-images;android-37.0;google_apis;$(sdk_arch)"

rm -rf "${state}/avds"
mkdir -p "${state}/avds"
printf '0\n' > "${state}/running"
: > "${state}/avd-operations"
: > "${state}/adb-calls"
printf '%s\n' "${non_play_image}" > "${state}/create-image-override"
if "${REPO_ROOT}/scripts/emulator.sh" create phone >"${tmpdir}/wrong-create.log" 2>&1; then
  fail "successful create with a wrong registration passed validation"
fi
[[ "$(avd_image_for_name "${PHONE_AVD}")" == "${non_play_image}" ]] || \
  fail "post-create validation mutated the wrong registration"
[[ "$(wc -l < "${state}/avd-operations" | tr -d ' ')" == "1" ]] || \
  fail "post-create validation performed an unexpected AVD mutation"
grep -Fq "explicitly run scripts/emulator.sh stop phone, then scripts/emulator.sh delete phone, then scripts/bootstrap.sh" \
  "${tmpdir}/wrong-create.log" || fail "post-create validation omitted its recovery command"
rm "${state}/create-image-override"

rm -rf "${state}/avds"
mkdir -p "${state}/avds"
: > "${state}/avd-operations"
"${REPO_ROOT}/scripts/emulator.sh" create phone >/dev/null 2>&1 || \
  fail "missing canonical AVD was not created"
[[ "$(avd_image_for_name "${PHONE_AVD}")" == "${target_image}" ]] || \
  fail "created phone AVD did not use the pinned Google Play image"
grep -Fxq "create:${PHONE_AVD}:${target_image}" "${state}/avd-operations" || \
  fail "phone AVD creation did not request the pinned Google Play image"

reset_avd "${PHONE_AVD}" "${legacy_image}" 0
mismatch_out="${tmpdir}/mismatch.log"
if "${REPO_ROOT}/scripts/emulator.sh" create phone >"${mismatch_out}" 2>&1; then
  fail "mismatched canonical AVD was replaced"
fi
[[ "$(avd_image_for_name "${PHONE_AVD}")" == "${legacy_image}" ]] || \
  fail "mismatched AVD refusal changed its image"
[[ ! -s "${state}/avd-operations" ]] || fail "mismatched AVD refusal mutated state"
grep -Fq "explicitly run scripts/emulator.sh stop phone, then scripts/emulator.sh delete phone, then scripts/bootstrap.sh" \
  "${mismatch_out}" || fail "mismatched AVD refusal omitted its recovery command"

reset_avd "${PHONE_AVD}" "${target_image}" 0
rm "${state}/avds/${PHONE_AVD}/config.ini"
if "${REPO_ROOT}/scripts/emulator.sh" create phone >"${mismatch_out}" 2>&1; then
  fail "AVD with unreadable image metadata passed provisioning"
fi
grep -Fq "explicitly run scripts/emulator.sh stop phone, then scripts/emulator.sh delete phone, then scripts/bootstrap.sh" \
  "${mismatch_out}" || fail "unknown AVD image omitted its recovery command"
[[ ! -e "${state}/avds/${PHONE_AVD}/config.ini" ]] || \
  fail "unknown AVD image metadata was replaced"
[[ ! -s "${state}/avd-operations" ]] || fail "unknown AVD image refusal mutated state"

reset_avd "${PHONE_AVD}" "${target_image}" 0
printf 'image.sysdir.1=%s/\n' "${non_play_image//;/\/}" >> \
  "${state}/avds/${PHONE_AVD}/config.ini"
if "${REPO_ROOT}/scripts/emulator.sh" create phone >"${mismatch_out}" 2>&1; then
  fail "AVD with duplicate image metadata passed provisioning"
fi
[[ "$(grep -c '^image\.sysdir\.1=' "${state}/avds/${PHONE_AVD}/config.ini")" == "2" ]] || \
  fail "duplicate AVD image metadata was replaced"
[[ ! -s "${state}/avd-operations" ]] || fail "duplicate AVD image refusal mutated state"

reset_avd "${PHONE_AVD}" "${target_image}" 0
"${REPO_ROOT}/scripts/emulator.sh" create phone >/dev/null 2>&1 || \
  fail "matching canonical AVD was rejected"
[[ "$(avd_image_for_name "${PHONE_AVD}")" == "${target_image}" ]] || \
  fail "matching AVD idempotence changed its image"
[[ ! -s "${state}/avd-operations" ]] || fail "matching AVD idempotence mutated state"

custom_avd="putio-phone-recovery-test"
reset_avd "${custom_avd}" "${non_play_image}" 0
custom_out="${tmpdir}/custom-mismatch.log"
if "${REPO_ROOT}/scripts/emulator.sh" create phone --name "${custom_avd}" >"${custom_out}" 2>&1; then
  fail "mismatched custom AVD passed provisioning"
fi
[[ "$(avd_image_for_name "${custom_avd}")" == "${non_play_image}" ]] || \
  fail "custom AVD mismatch changed its registration"
[[ ! -s "${state}/avd-operations" ]] || fail "custom AVD mismatch mutated state"
grep -Fq "explicitly run scripts/emulator.sh stop phone --name ${custom_avd}, then scripts/emulator.sh delete phone --name ${custom_avd}, then scripts/emulator.sh create phone --name ${custom_avd}" \
  "${custom_out}" || fail "custom AVD mismatch targeted the wrong recovery AVD"

reset_avd "${custom_avd}" "${target_image}" 1
"${REPO_ROOT}/scripts/emulator.sh" stop phone --name "${custom_avd}" >/dev/null 2>&1 || \
  fail "named AVD recovery stop failed"
[[ "$(<"${state}/running")" == "0" ]] || fail "named AVD recovery stop left it running"

reset_avd "${PHONE_AVD}" "${non_play_image}" 0
reset_runtime
if "${REPO_ROOT}/scripts/emulator.sh" boot phone --headless >/dev/null 2>&1; then
  fail "same-API non-Play phone image passed boot readiness"
fi
[[ "$(<"${state}/running")" == "0" ]] || fail "image mismatch started an emulator"
grep -q '^start-server$' "${state}/adb-calls" && fail "image mismatch reached adb startup"

reset_runtime
prepare_device emulator-5554 phone >/dev/null 2>&1 || fail "valid phone runtime was rejected"

runtime_out="${tmpdir}/runtime-readiness.log"
reset_runtime
printf '0\n' > "${state}/api-query-succeeds"
if (prepare_device emulator-5554 phone) >"${runtime_out}" 2>&1; then
  fail "failed API query passed readiness"
fi
grep -Fq "explicitly run scripts/emulator.sh stop emulator-5554, then scripts/emulator.sh delete phone, then scripts/bootstrap.sh" \
  "${tmpdir}/runtime-readiness.log" || fail "failed API query omitted its recovery command"

reset_runtime 37 0
if (prepare_device emulator-5554 phone) >"${runtime_out}" 2>&1; then
  fail "missing Chrome passed readiness"
fi
grep -q "add-role-holder" "${state}/adb-calls" && fail "missing Chrome reached role assignment"
grep -Fq "explicitly run scripts/emulator.sh stop emulator-5554, then scripts/emulator.sh delete phone, then scripts/bootstrap.sh" \
  "${runtime_out}" || fail "runtime readiness failure omitted its recovery command"

reset_runtime 37 1 1 comxandroid.chrome
if (prepare_device emulator-5554 phone) >/dev/null 2>&1; then
  fail "regex-like browser role holder passed readiness"
fi
grep -q "query-services" "${state}/adb-calls" && fail "wrong role holder reached Auth Tab query"

reset_runtime 37 1 1 $'com.android.chrome\ncom.example.browser'
if (prepare_device emulator-5554 phone) >/dev/null 2>&1; then
  fail "multiple browser role holders passed readiness"
fi
grep -q "query-services" "${state}/adb-calls" && fail "multiple role holders reached Auth Tab query"

reset_runtime
printf '0\n' > "${state}/role-query-succeeds"
if (prepare_device emulator-5554 phone) >"${runtime_out}" 2>&1; then
  fail "failed browser role query passed readiness"
fi
grep -Fq "explicitly run scripts/emulator.sh stop emulator-5554, then scripts/emulator.sh delete phone, then scripts/bootstrap.sh" \
  "${runtime_out}" || fail "failed role query omitted its recovery command"

reset_runtime
printf '0\n' > "${state}/version-query-succeeds"
prepare_device emulator-5554 phone >"${runtime_out}" 2>&1 || \
  fail "failed Chrome version query rejected a ready runtime"
grep -Fq "Chrome unknown" "${runtime_out}" || fail "failed Chrome version query was not logged as unknown"

reset_runtime 37 1 1 com.android.chrome ""
if (prepare_device emulator-5554 phone) >/dev/null 2>&1; then
  fail "missing Auth Tab service passed readiness"
fi

reset_runtime
printf '0\n' > "${state}/auth-tab-query-succeeds"
if (prepare_device emulator-5554 phone) >"${runtime_out}" 2>&1; then
  fail "failed Auth Tab service query passed readiness"
fi
grep -Fq "explicitly run scripts/emulator.sh stop emulator-5554, then scripts/emulator.sh delete phone, then scripts/bootstrap.sh" \
  "${runtime_out}" || fail "failed Auth Tab query omitted its recovery command"
grep -q " install " "${state}/adb-calls" && fail "failed Auth Tab query reached app install"

reset_runtime 36
if (prepare_device emulator-5554 phone) >/dev/null 2>&1; then
  fail "wrong API level passed readiness"
fi
grep -q "pm path" "${state}/adb-calls" && fail "wrong API level reached Chrome preparation"

reset_runtime 37 1 0
if (prepare_device emulator-5554 phone) >/dev/null 2>&1; then
  fail "failed browser role assignment passed readiness"
fi
grep -q "query-services" "${state}/adb-calls" && fail "role failure reached Auth Tab query"

reset_runtime 37 1 1 com.android.chrome com.example.browser
if (prepare_device emulator-5554 phone) >/dev/null 2>&1; then
  fail "wrong Auth Tab provider passed readiness"
fi
grep -q " install " "${state}/adb-calls" && fail "readiness failure reached app install"

# prove.sh must stop between boot readiness and install when the real
# emulator.sh rejects the browser's Auth Tab service.
reset_avd "${PHONE_AVD}" "${target_image}" 1
reset_runtime 37 1 1 com.android.chrome com.example.browser
prove_out="${tmpdir}/prove-negative.log"
fake_apk="${tmpdir}/app.apk"
: > "${fake_apk}"
if PUTIO_PROVE_APK="${fake_apk}" "${REPO_ROOT}/scripts/prove.sh" mobile --skip-build >"${prove_out}" 2>&1; then
  fail "prove.sh accepted a phone without Chrome Auth Tab support"
fi
grep -q '^PROOF FAIL mobile$' "${prove_out}" || fail "negative proof omitted its failure marker"
grep -q " install " "${state}/adb-calls" && fail "negative proof attempted app install"
[[ "$(<"${state}/running")" == "1" ]] || fail "negative proof stopped a reused emulator"
grep -q " emu kill" "${state}/adb-calls" && fail "negative proof killed a reused emulator"

# A readiness failure after this invocation starts an emulator must stop that
# exact process. prove.sh must also delete an ephemeral registration it created.
reset_avd "${PHONE_AVD}" "${target_image}" 0
reset_runtime 37 1 1 com.android.chrome com.example.browser
if "${REPO_ROOT}/scripts/emulator.sh" boot phone --headless >/dev/null 2>&1; then
  fail "fresh boot accepted a phone without Chrome Auth Tab support"
fi
[[ "$(<"${state}/running")" == "0" ]] || fail "fresh readiness failure leaked its emulator"

reset_avd "${PHONE_AVD}" "${target_image}" 0
reset_runtime 37 1 1 com.android.chrome com.example.browser
if PUTIO_PROVE_APK="${fake_apk}" "${REPO_ROOT}/scripts/prove.sh" mobile --skip-build --ephemeral >"${prove_out}" 2>&1; then
  fail "ephemeral proof accepted a phone without Chrome Auth Tab support"
fi
[[ "$(<"${state}/running")" == "0" ]] || fail "ephemeral readiness failure leaked its emulator"
if find "${state}/avds" -mindepth 1 -maxdepth 1 -type d -name 'putio-phone-eph-*' | grep -q .; then
  fail "ephemeral readiness failure leaked its AVD registration"
fi

echo "emulator contract tests passed"
