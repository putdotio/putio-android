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

cmd="${1:-}"; shift || { print_usage "${BASH_SOURCE[0]}"; exit 64; }

SERIAL=""
LABEL="capture"
SECONDS_ARG=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="${2:?--serial requires a value}"; shift ;;
    --label) LABEL="${2:?--label requires a value}"; shift ;;
    --seconds) SECONDS_ARG="${2:?--seconds requires a value}"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# screenrecord hard-caps --time-limit at 180 s and rejects larger values.
[[ "${SECONDS_ARG}" =~ ^[0-9]+$ && "${SECONDS_ARG}" -ge 1 && "${SECONDS_ARG}" -le 180 ]] || \
  die "--seconds must be 1-180 (screenrecord limit), got '${SECONDS_ARG}'"

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
    # A truncated screencap is not a PNG; check the magic bytes.
    if [[ "$(head -c 4 "${out}" | xxd -p)" != "89504e47" ]]; then
      mv "${out}" "${out}.corrupt"
      die "screencap output is not a PNG; kept as ${out}.corrupt"
    fi
    # Content policy stays with callers (prove.sh gates its launch shots):
    # dark-theme or playback captures can be legitimately near-black, so a
    # generic capture only warns.
    if command -v ffprobe >/dev/null 2>&1; then
      luma="$(ffprobe -v error -f lavfi -i "movie=${out},signalstats" \
        -show_entries frame_tags=lavfi.signalstats.YAVG -of default=nk=1:nw=1 2>/dev/null | head -1 || true)"
      luma="${luma%%.*}"
      if [[ "${luma}" =~ ^[0-9]+$ ]] && (( luma < 8 )); then
        log "WARNING: screenshot is near-black (mean luma ${luma}); verify it shows what you expect"
      fi
    fi
    log "screenshot: ${out}"
    echo "${out}"
    ;;
  record)
    out="${EVIDENCE_DIR}/${STAMP}-${LABEL}.mp4"
    remote="/data/local/tmp/putio-evidence.mp4"
    "${ADB}" -s "${SERIAL}" shell screenrecord --time-limit "${SECONDS_ARG}" "${remote}"
    # screenrecord can return before the muxer finishes the container; a pull
    # that races it produces an mp4 with no moov atom (unplayable).
    sleep 2
    "${ADB}" -s "${SERIAL}" pull "${remote}" "${out}" >/dev/null
    "${ADB}" -s "${SERIAL}" shell rm -f "${remote}"
    [[ -s "${out}" ]] || { rm -f "${out}"; die "screenrecord produced no data"; }
    # Integrity gate: a truncated screenrecord container still carries a moov
    # atom but no parseable duration, so ffprobe is the reliable check.
    if command -v ffprobe >/dev/null 2>&1; then
      dur="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "${out}" 2>/dev/null || true)"
      if [[ ! "${dur}" =~ ^[0-9] ]]; then
        # Quarantine with a suffix so the publish flow can't pick it up.
        mv "${out}" "${out}.corrupt"
        die "recording is corrupt (no parseable duration); kept as ${out}.corrupt"
      fi
    else
      log "WARNING: ffprobe not found; recording integrity not verified"
    fi
    log "recording: ${out}"
    echo "${out}"
    ;;
  *)
    die "unknown command: ${cmd} (expected screenshot|record)"
    ;;
esac
