#!/usr/bin/env bash
# Capture visual evidence from a running emulator/device into .evidence/.
#
#   scripts/evidence.sh screenshot [--serial SERIAL] [--label LABEL]
#   scripts/evidence.sh record [--serial SERIAL] [--label LABEL] [--seconds N]
#
# Output: .evidence/<UTC timestamp>-<label>.png|.mp4 (path printed on stdout).
# .evidence/ is gitignored; publish files with the attach CLI, never commit.
# With one device connected --serial is optional.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_sdk_root

cmd="${1:-}"; shift || { sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 64; }

SERIAL=""
LABEL="capture"
SECONDS_ARG=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift ;;
    --label) LABEL="$2"; shift ;;
    --seconds) SECONDS_ARG="$2"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

if [[ -z "${SERIAL}" ]]; then
  devices="$("${ADB}" devices | awk '$2 == "device" {print $1}')"
  count="$(printf '%s' "${devices}" | grep -c . || true)"
  [[ "${count}" -eq 1 ]] || die "expected exactly one device, found ${count}; pass --serial"
  SERIAL="${devices}"
fi

EVIDENCE_DIR="${REPO_ROOT}/.evidence"
mkdir -p "${EVIDENCE_DIR}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"

case "${cmd}" in
  screenshot)
    out="${EVIDENCE_DIR}/${STAMP}-${LABEL}.png"
    "${ADB}" -s "${SERIAL}" exec-out screencap -p > "${out}"
    [[ -s "${out}" ]] || { rm -f "${out}"; die "screencap produced no data"; }
    log "screenshot: ${out}"
    echo "${out}"
    ;;
  record)
    out="${EVIDENCE_DIR}/${STAMP}-${LABEL}.mp4"
    remote="/data/local/tmp/putio-evidence.mp4"
    "${ADB}" -s "${SERIAL}" shell screenrecord --time-limit "${SECONDS_ARG}" "${remote}"
    "${ADB}" -s "${SERIAL}" pull "${remote}" "${out}" >/dev/null
    "${ADB}" -s "${SERIAL}" shell rm -f "${remote}"
    [[ -s "${out}" ]] || { rm -f "${out}"; die "screenrecord produced no data"; }
    log "recording: ${out}"
    echo "${out}"
    ;;
  *)
    die "unknown command: ${cmd} (expected screenshot|record)"
    ;;
esac
