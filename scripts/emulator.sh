#!/usr/bin/env bash
# Manage the repo's emulators: putio-phone and putio-tv.
#
#   scripts/emulator.sh create <phone|tv> [--name NAME]
#   scripts/emulator.sh boot   <phone|tv> [--headless] [--name NAME]
#   scripts/emulator.sh stop   <phone|tv> [--name NAME]
#   scripts/emulator.sh stop   <emulator-NNNN>
#   scripts/emulator.sh delete <phone|tv> [--name NAME]
#   scripts/emulator.sh status
#
# Ownership contract:
#   - `boot` reuses a running emulator for the same AVD (prints its serial,
#     does not own it). When it starts a new process, that exact pid/serial is
#     owned until boot completes: on failure, SIGINT, or SIGTERM the owned
#     process is killed and confirmed gone from `adb devices` before exit.
#     On successful boot, ownership passes to the caller; `stop` is the
#     counterpart.
#   - `stop` targets one serial only (`adb -s <serial> emu kill`); it never
#     pattern-matches process names or kills emulators of other AVDs.
#   - `delete` removes an AVD registration and never touches running
#     emulators; it refuses while the AVD is in use.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_sdk_root

BOOT_TIMEOUT_SECONDS="${PUTIO_EMULATOR_BOOT_TIMEOUT:-600}"

usage() { print_usage "${BASH_SOURCE[0]}"; exit 64; }

cmd="${1:-}"; shift || usage

parse_profile_args() {
  PROFILE=""
  NAME=""
  HEADLESS="${PUTIO_EMULATOR_HEADLESS:-0}"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      phone|tv) PROFILE="$1" ;;
      --name) NAME="${2:?--name requires a value}"; shift ;;
      --headless) HEADLESS=1 ;;
      *) die "unknown argument: $1" ;;
    esac
    shift
  done
  [[ -n "${PROFILE}" ]] || usage
  [[ -n "${NAME}" ]] || NAME="$(avd_name_for "${PROFILE}")"
  validate_avd_name "${NAME}"
}

require_matching_avd_image() {
  local expected_image current_image stop_command delete_command recreate_command
  expected_image="$(image_for "${PROFILE}")"
  stop_command="$(avd_stop_command "${PROFILE}" "${NAME}")"
  delete_command="$(avd_delete_command "${PROFILE}" "${NAME}")"
  recreate_command="$(avd_recreate_command "${PROFILE}" "${NAME}")"
  current_image="$(avd_image_for_name "${NAME}")" || \
    die "could not determine the system image for existing AVD ${NAME}; explicitly run ${stop_command}, then ${delete_command}, then ${recreate_command}"
  [[ "${current_image}" == "${expected_image}" ]] || \
    die "AVD ${NAME} uses ${current_image}; expected ${expected_image}; explicitly run ${stop_command}, then ${delete_command}, then ${recreate_command}"
}

do_create() {
  parse_profile_args "$@"
  local image create_out
  image="$(image_for "${PROFILE}")"
  [[ -d "${SDK_ROOT}/$(echo "${image}" | tr ';' '/')" ]] || \
    die "system image ${image} not installed; run scripts/bootstrap.sh"
  if avd_registered "${NAME}"; then
    require_matching_avd_image
    log "AVD ${NAME} already exists (${image})"
    return 0
  fi
  create_out="$(echo "no" | "${AVDMANAGER}" create avd \
    --name "${NAME}" \
    --package "${image}" \
    --device "$(device_for "${PROFILE}")" 2>&1)" || \
    die "could not create AVD ${NAME}: ${create_out}"
  require_matching_avd_image
  log "created AVD ${NAME} (${image})"
}

do_boot() {
  parse_profile_args "$@"
  avd_registered "${NAME}" || die "AVD ${NAME} does not exist; run scripts/emulator.sh create ${PROFILE}"
  require_matching_avd_image

  "${ADB}" start-server >/dev/null 2>&1

  local existing
  if existing="$(serial_for_avd "${NAME}")"; then
    log "reusing running emulator ${existing} (AVD ${NAME}, not owned by this invocation)"
    # adb reports 'device' before boot completes; a reused emulator that is
    # still booting must pass the same readiness bar as a fresh one — waited
    # on, never claimed or stopped.
    local reuse_deadline=$(( $(date +%s) + BOOT_TIMEOUT_SECONDS ))
    until [[ "$("${ADB}" -s "${existing}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
      (( $(date +%s) < reuse_deadline )) || die "reused emulator ${existing} did not finish booting within ${BOOT_TIMEOUT_SECONDS}s"
      sleep 2
    done
    until "${ADB}" -s "${existing}" shell pm path android >/dev/null 2>&1; do
      (( $(date +%s) < reuse_deadline )) || die "reused emulator ${existing}: package manager not ready within ${BOOT_TIMEOUT_SECONDS}s"
      sleep 2
    done
    prepare_device "${existing}" "${PROFILE}" "${NAME}"
    echo "${existing}"
    return 0
  fi

  local port serial pid logfile
  port="$(free_emulator_port)"
  serial="emulator-${port}"
  logfile="${REPO_ROOT}/.evidence/logs/emulator-${NAME}-${port}.log"
  mkdir -p "$(dirname "${logfile}")"

  local flags=(-avd "${NAME}" -port "${port}" -no-snapshot -no-boot-anim -no-audio)
  if [[ "${HEADLESS}" == "1" ]]; then
    # swiftshader_indirect is slower than auto-no-window but renders
    # deterministically; auto-no-window intermittently composites the app
    # window black under host load, which fails prove.sh's pixel gate.
    flags+=(-no-window -gpu swiftshader_indirect)
  fi

  log "booting ${NAME} on ${serial} (headless=${HEADLESS}, log: ${logfile})"
  "${EMULATOR_BIN}" "${flags[@]}" >"${logfile}" 2>&1 &
  pid=$!
  # Callers (prove.sh) can learn the spawned serial before boot completes, so
  # their cleanup can stop it even if this process dies mid-handoff.
  if [[ -n "${PUTIO_BOOT_STATE_FILE:-}" ]]; then
    echo "${serial} ${pid}" > "${PUTIO_BOOT_STATE_FILE}"
  fi

  BOOT_SIGNAL_CODE=""
  cleanup_owned_boot() {
    local code=$?
    if [[ -n "${BOOT_SIGNAL_CODE}" ]]; then code="${BOOT_SIGNAL_CODE}"; fi
    trap - EXIT INT TERM
    log "boot did not complete (exit ${code}); stopping owned emulator pid ${pid} (${serial})"
    # Port selection can race a concurrent boot. A live pid proves we bound
    # the console port (the emulator exits immediately when the port is
    # taken), so the serial is ours to kill; a dead pid means anything on
    # that serial belongs to another invocation and must be left alone.
    if kill -0 "${pid}" 2>/dev/null; then
      "${ADB}" -s "${serial}" emu kill >/dev/null 2>&1 || true
      kill "${pid}" >/dev/null 2>&1 || true
      if ! wait_serial_gone "${serial}" 20; then
        log "ERROR: owned emulator ${serial} still present after cleanup"
        exit 70
      fi
      log "owned emulator ${serial} confirmed gone"
    else
      log "owned emulator process ${pid} already exited; ${serial} not ours to stop"
      # Our corpse must still clear from adb; a live 'device' entry belongs
      # to whoever won the port and is left alone.
      if ! wait_serial_gone "${serial}" 15; then
        local state
        state="$("${ADB}" devices 2>/dev/null | awk -v s="${serial}" '$1 == s {print $2}')"
        if [[ "${state}" != "device" ]]; then
          log "ERROR: dead emulator ${serial} still listed (${state:-absent?}) in adb devices"
          exit 70
        fi
        log "${serial} now serves another invocation; leaving it"
      fi
    fi
    exit "${code}"
  }
  trap cleanup_owned_boot EXIT
  trap 'BOOT_SIGNAL_CODE=130 cleanup_owned_boot' INT
  trap 'BOOT_SIGNAL_CODE=143 cleanup_owned_boot' TERM

  local deadline=$(( $(date +%s) + BOOT_TIMEOUT_SECONDS ))
  until [[ "$("${ADB}" -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    kill -0 "${pid}" 2>/dev/null || { log "emulator process exited early; see ${logfile}"; exit 1; }
    (( $(date +%s) < deadline )) || { log "boot timed out after ${BOOT_TIMEOUT_SECONDS}s"; exit 1; }
    sleep 2
  done

  # sys.boot_completed can flip before the package manager accepts installs;
  # wait until pm answers so callers can install immediately.
  until "${ADB}" -s "${serial}" shell pm path android >/dev/null 2>&1; do
    kill -0 "${pid}" 2>/dev/null || { log "emulator process exited early; see ${logfile}"; exit 1; }
    (( $(date +%s) < deadline )) || { log "package manager not ready after ${BOOT_TIMEOUT_SECONDS}s"; exit 1; }
    sleep 2
  done

  prepare_device "${serial}" "${PROFILE}" "${NAME}"

  trap - EXIT INT TERM
  log "booted ${NAME} on ${serial} (pid ${pid}); stop with scripts/emulator.sh stop ${PROFILE}"
  echo "${serial}"
}

do_stop() {
  local target="${1:-}" serial
  [[ -n "${target}" ]] || usage
  case "${target}" in
    emulator-*)
      [[ $# -eq 1 ]] || usage
      serial="${target}"
      ;;
    phone|tv)
      parse_profile_args "$@"
      serial="$(serial_for_avd "${NAME}")" || \
        { log "no running emulator for AVD ${NAME}"; return 0; }
      ;;
    *) die "unknown stop target: ${target}" ;;
  esac
  log "stopping ${serial}"
  "${ADB}" -s "${serial}" emu kill >/dev/null 2>&1 || true
  wait_serial_gone "${serial}" 30 || die "emulator ${serial} did not stop"
  log "stopped ${serial}"
}

do_delete() {
  parse_profile_args "$@"
  avd_registered "${NAME}" || { log "AVD ${NAME} does not exist"; return 0; }
  if serial="$(serial_for_avd "${NAME}")"; then
    die "AVD ${NAME} is in use by ${serial}; stop it first"
  fi
  "${AVDMANAGER}" delete avd --name "${NAME}" >/dev/null
  log "deleted AVD ${NAME}"
}

do_status() {
  local serial avd
  local found=0
  while read -r serial; do
    avd="$("${ADB}" -s "${serial}" emu avd name 2>/dev/null | head -1 | tr -d '\r')" || avd="?"
    echo "${serial}  ${avd}"
    found=1
  done < <("${ADB}" devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1}')
  [[ "${found}" == "1" ]] || echo "no running emulators"
}

case "${cmd}" in
  create) do_create "$@" ;;
  boot) do_boot "$@" ;;
  stop) do_stop "$@" ;;
  delete) do_delete "$@" ;;
  status) do_status ;;
  *) usage ;;
esac
