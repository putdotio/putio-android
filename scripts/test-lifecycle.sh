#!/usr/bin/env bash
# Emulator-lifecycle proof for scripts/prove.sh (forced failure, SIGINT,
# SIGTERM, preexisting-emulator preservation, ephemeral AVD cleanup).
#
#   scripts/test-lifecycle.sh
#
# Needs a completed bootstrap and a built mobile debug APK (built here if
# missing). Boots emulators several times; expect ~5-10 minutes.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_sdk_root

PROVE="${REPO_ROOT}/scripts/prove.sh"
EMU="${REPO_ROOT}/scripts/emulator.sh"
APK="${REPO_ROOT}/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk"

fail() { log "LIFECYCLE FAIL: $*"; exit 1; }

assert_serial_gone() {
  "${ADB}" devices | awk '{print $1}' | grep -qx "$1" && fail "$2: serial $1 still present" || true
}
assert_serial_present() {
  "${ADB}" devices | awk '{print $1}' | grep -qx "$1" || fail "$2: serial $1 not running"
}
assert_avd_exists() {
  avd_exists "$1" || fail "$2: AVD $1 missing"
}
assert_avd_absent() {
  avd_exists "$1" && fail "$2: AVD $1 still registered" || true
}
booted_serial() {
  sed -n 's/^BOOTED //p' "$1" | head -1 | tr -d '\r'
}

[[ -f "${APK}" ]] || (cd "${REPO_ROOT}" && ./gradlew -q :app:assembleMobileDebug)

# No emulator may be running at the start; the assertions depend on it.
if "${ADB}" devices | awk '$1 ~ /^emulator-/ {found=1} END {exit !found}'; then
  fail "precondition: emulators already running; stop them first (scripts/emulator.sh status)"
fi

tmpdir="$(mktemp -d)"
PRE_SERIAL=""
cleanup_suite() {
  rm -rf "${tmpdir}"
  # The suite must honor the contract it proves: stop the case-4 emulator it
  # booted even when an assertion fails mid-case.
  if [[ -n "${PRE_SERIAL}" ]] && "${ADB}" devices | awk '{print $1}' | grep -qx "${PRE_SERIAL}"; then
    "${EMU}" stop "${PRE_SERIAL}" || true
  fi
}
trap cleanup_suite EXIT

# --- case 1: forced failure stops the owned emulator, keeps the reusable AVD
log "case 1: forced failure after boot"
out="${tmpdir}/case1.log"
if PUTIO_PROVE_FAIL_AT=after-boot "${PROVE}" mobile --skip-build >"${out}" 2>&1; then
  fail "case 1: prove.sh unexpectedly succeeded"
fi
serial="$(booted_serial "${out}")"
[[ -n "${serial}" ]] || fail "case 1: no BOOTED marker (log: $(cat "${out}"))"
grep -q "PROOF FAIL mobile" "${out}" || fail "case 1: missing PROOF FAIL marker"
assert_serial_gone "${serial}" "case 1"
assert_avd_exists "${PHONE_AVD}" "case 1"
log "case 1 passed (owned ${serial} stopped, ${PHONE_AVD} preserved)"

# --- cases 2 and 3: SIGINT / SIGTERM after boot
for sig in INT TERM; do
  log "case ${sig}: SIG${sig} after boot"
  out="${tmpdir}/case-${sig}.log"
  # Without job control, background jobs inherit SIGINT=ignore and prove.sh
  # could never trap it; -m gives the child default signal dispositions.
  set -m
  "${PROVE}" mobile --skip-build >"${out}" 2>&1 &
  pid=$!
  set +m
  deadline=$(( $(date +%s) + 300 ))
  until grep -q '^BOOTED ' "${out}" 2>/dev/null; do
    kill -0 "${pid}" 2>/dev/null || fail "case ${sig}: prove.sh exited before boot ($(tail -5 "${out}"))"
    (( $(date +%s) < deadline )) || fail "case ${sig}: boot timed out"
    sleep 2
  done
  serial="$(booted_serial "${out}")"
  kill "-${sig}" "${pid}"
  wait "${pid}" && fail "case ${sig}: prove.sh exited 0 after SIG${sig}" || true
  assert_serial_gone "${serial}" "case ${sig}"
  assert_avd_exists "${PHONE_AVD}" "case ${sig}"
  log "case ${sig} passed (owned ${serial} stopped on SIG${sig})"
done

# --- case 4: preexisting emulator is reused and never stopped; an ephemeral
# --- run alongside it stops only its own emulator and deletes only its AVD
log "case 4: preexisting emulator preserved across reuse-failure and ephemeral runs"
PRE_SERIAL="$("${EMU}" boot phone --headless | tail -1)"
[[ "${PRE_SERIAL}" == emulator-* ]] || fail "case 4: could not boot preexisting emulator"

out="${tmpdir}/case4-reuse.log"
if PUTIO_PROVE_FAIL_AT=after-boot "${PROVE}" mobile --skip-build >"${out}" 2>&1; then
  fail "case 4: reuse prove.sh unexpectedly succeeded"
fi
[[ "$(booted_serial "${out}")" == "${PRE_SERIAL}" ]] || fail "case 4: reuse run did not reuse ${PRE_SERIAL}"
assert_serial_present "${PRE_SERIAL}" "case 4 (after reuse failure)"

out="${tmpdir}/case4-eph.log"
if PUTIO_PROVE_FAIL_AT=after-install "${PROVE}" mobile --skip-build --ephemeral >"${out}" 2>&1; then
  fail "case 4: ephemeral prove.sh unexpectedly succeeded"
fi
eph_serial="$(booted_serial "${out}")"
[[ -n "${eph_serial}" && "${eph_serial}" != "${PRE_SERIAL}" ]] || fail "case 4: ephemeral run had no distinct serial"
eph_avd="$(sed -n 's/.*creating ephemeral AVD \(putio-phone-eph-[0-9-]*\).*/\1/p' "${out}" | head -1)"
[[ -n "${eph_avd}" ]] || fail "case 4: could not determine ephemeral AVD name"
assert_serial_gone "${eph_serial}" "case 4 (ephemeral)"
assert_avd_absent "${eph_avd}" "case 4 (ephemeral)"
assert_serial_present "${PRE_SERIAL}" "case 4 (after ephemeral failure)"
assert_avd_exists "${PHONE_AVD}" "case 4"

"${EMU}" stop "${PRE_SERIAL}"
log "case 4 passed (preexisting ${PRE_SERIAL} survived both runs, ephemeral ${eph_serial}/${eph_avd} fully cleaned)"

log "LIFECYCLE PASS: all cases green"
echo "LIFECYCLE PASS"
