#!/usr/bin/env bash
# Capture visual evidence from a running emulator/device into .evidence/.
#
#   scripts/evidence.sh screenshot [--serial SERIAL] [--label LABEL] [--allow-dark]
#   scripts/evidence.sh record [--serial SERIAL] [--label LABEL] [--seconds N] [--allow-dark]
#
# Output: .evidence/<UTC timestamp>-<label>.png|.mp4 (path printed on stdout).
# Near-black captures are quarantined (*.black.*) and fail the command unless
# --allow-dark is passed for content that is legitimately dark (playback,
# dark scenes). Corrupt output is quarantined as *.corrupt.
# .evidence/ is gitignored; publish files with the attach CLI, never commit.
# With one device connected --serial is optional.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_sdk_root

command -v ffprobe >/dev/null 2>&1 || die "ffprobe required for evidence validation; run scripts/bootstrap.sh (installs ffmpeg)"

cmd="${1:-}"; shift || { print_usage "${BASH_SOURCE[0]}"; exit 64; }

SERIAL=""
LABEL="capture"
SECONDS_ARG=10
ALLOW_DARK=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="${2:?--serial requires a value}"; shift ;;
    --label) LABEL="${2:?--label requires a value}"; shift ;;
    --seconds) SECONDS_ARG="${2:?--seconds requires a value}"; shift ;;
    --allow-dark) ALLOW_DARK=1 ;;
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

# Mean luma across all frames (a PNG is one frame); integer, 0 on failure.
mean_luma() {
  ffprobe -v error -f lavfi -i "movie=$1,signalstats" \
    -show_entries frame_tags=lavfi.signalstats.YAVG -of csv=p=0 2>/dev/null | \
    awk -F, 'NF { sum += $1; n += 1 } END { if (n) printf "%d", sum / n; else print 0 }'
}

# Quarantine $1 with suffix .black.<ext> and fail, unless --allow-dark.
# Threshold $2 depends on the pixel range: PNG screenshots are full-range
# (black = 0, threshold 8); H.264 recordings are video-range (black = 16,
# threshold 20).
gate_dark() {
  local file="$1" threshold="$2" luma
  luma="$(mean_luma "${file}")"
  if (( luma >= threshold )); then
    return 0
  fi
  if [[ "${ALLOW_DARK}" == "1" ]]; then
    log "near-black capture (mean luma ${luma}) kept: --allow-dark"
    return 0
  fi
  local quarantined="${file%.*}.black.${file##*.}"
  mv "${file}" "${quarantined}"
  die "capture is near-black (mean luma ${luma}); quarantined as ${quarantined} — pass --allow-dark for legitimately dark content"
}

case "${cmd}" in
  screenshot)
    out="${EVIDENCE_DIR}/${STAMP}-${LABEL}.png"
    # Guard capture commands: under set -e an adb failure would otherwise
    # exit before cleanup, stranding a partial file under a publishable name.
    if ! "${ADB}" -s "${SERIAL}" exec-out screencap -p > "${out}"; then
      rm -f "${out}"
      die "screencap failed on ${SERIAL}"
    fi
    [[ -s "${out}" ]] || { rm -f "${out}"; die "screencap produced no data"; }
    # A truncated screencap is not a PNG; check the magic bytes.
    if [[ "$(head -c 4 "${out}" | xxd -p)" != "89504e47" ]]; then
      mv "${out}" "${out}.corrupt"
      die "screencap output is not a PNG; kept as ${out}.corrupt"
    fi
    gate_dark "${out}" 8
    log "screenshot: ${out}"
    echo "${out}"
    ;;
  record)
    out="${EVIDENCE_DIR}/${STAMP}-${LABEL}.mp4"
    remote="/data/local/tmp/putio-evidence.mp4"
    # The encoder can be unready shortly after boot: screenrecord then exits
    # nonzero (235) with an empty file. One bounded retry covers that window.
    if ! "${ADB}" -s "${SERIAL}" shell screenrecord --time-limit "${SECONDS_ARG}" "${remote}"; then
      log "screenrecord failed; retrying once in 5s"
      sleep 5
      "${ADB}" -s "${SERIAL}" shell screenrecord --time-limit "${SECONDS_ARG}" "${remote}" || \
        die "screenrecord failed twice on ${SERIAL}"
    fi
    # screenrecord can return before the muxer finishes the container; a pull
    # that races it produces an mp4 with no moov atom (unplayable).
    sleep 2
    if ! "${ADB}" -s "${SERIAL}" pull "${remote}" "${out}" >/dev/null; then
      rm -f "${out}"
      die "pull of ${remote} failed on ${SERIAL}"
    fi
    "${ADB}" -s "${SERIAL}" shell rm -f "${remote}" || true
    [[ -s "${out}" ]] || { rm -f "${out}"; die "screenrecord produced no data"; }
    # Integrity gate: a truncated screenrecord container still carries a moov
    # atom but no parseable duration, so ffprobe is the reliable check.
    dur="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "${out}" 2>/dev/null || true)"
    if [[ ! "${dur}" =~ ^[0-9] ]]; then
      mv "${out}" "${out}.corrupt"
      die "recording is corrupt (no parseable duration); kept as ${out}.corrupt"
    fi
    gate_dark "${out}" 20
    log "recording: ${out}"
    echo "${out}"
    ;;
  *)
    die "unknown command: ${cmd} (expected screenshot|record)"
    ;;
esac
