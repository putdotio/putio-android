#!/usr/bin/env bash
# Capture visual evidence from a running emulator/device into .evidence/.
#
#   scripts/evidence.sh screenshot [--serial SERIAL] [--label LABEL] [--allow-dark]
#   scripts/evidence.sh record [--serial SERIAL] [--label LABEL] [--seconds N] [--allow-dark] [--keep-idle]
#
# Output: .evidence/<UTC timestamp>-<label>.png|.mp4 (path printed on stdout).
# Near-black captures are quarantined (*.black.*) and fail the command unless
# --allow-dark is passed for content that is legitimately dark (playback,
# dark scenes). Corrupt output is quarantined as *.corrupt.
# .evidence/ is gitignored; publish files with the attach CLI, never commit.
# With one device connected --serial is optional.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
source "$(dirname "${BASH_SOURCE[0]}")/lib-recording.sh"
require_sdk_root

command -v ffprobe >/dev/null 2>&1 || die "ffprobe required for evidence validation; run scripts/bootstrap.sh (installs ffmpeg)"

cmd="${1:-}"; shift || { print_usage "${BASH_SOURCE[0]}"; exit 64; }

SERIAL=""
LABEL="capture"
SECONDS_ARG=10
ALLOW_DARK=0
KEEP_IDLE=0
# LABEL lands in an ffprobe filtergraph where commas/colons are syntax.
LABEL_CHARSET='^[A-Za-z0-9._-]+$'
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="${2:?--serial requires a value}"; shift ;;
    --label) LABEL="${2:?--label requires a value}"; shift ;;
    --seconds) SECONDS_ARG="${2:?--seconds requires a value}"; shift ;;
    --allow-dark) ALLOW_DARK=1 ;;
    --keep-idle) KEEP_IDLE=1 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# screenrecord hard-caps --time-limit at 180 s; the floor of 3 keeps every
# accepted length clear of the 2 s minimum-duration integrity gate below.
[[ "${SECONDS_ARG}" =~ ^[0-9]+$ && "${SECONDS_ARG}" -ge 3 && "${SECONDS_ARG}" -le 180 ]] || \
  die "--seconds must be 3-180 (screenrecord limit, 2s duration gate), got '${SECONDS_ARG}'"

[[ "${LABEL}" =~ ${LABEL_CHARSET} ]] || \
  die "--label may only contain letters, digits, dot, underscore, dash (got '${LABEL}')"

if [[ -z "${SERIAL}" ]]; then
  devices="$("${ADB}" devices | awk '$2 == "device" {print $1}')"
  count="$(printf '%s' "${devices}" | grep -c . || true)"
  [[ "${count}" -eq 1 ]] || die "expected exactly one device, found ${count}; pass --serial"
  SERIAL="${devices}"
fi

EVIDENCE_DIR="${EVIDENCE_DIR:-${REPO_ROOT}/.evidence}"
mkdir -p "${EVIDENCE_DIR}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"

# Mean luma across all frames (a PNG is one frame); integer, 0 on failure.
# The basename is charset-safe (validated LABEL + timestamp), but the
# directory part of the path is not — commas/colons are lavfi filtergraph
# syntax — so probe from inside the directory.
mean_luma() {
  (cd "$(dirname "$1")" && ffprobe -v error -f lavfi -i "movie=$(basename "$1"),signalstats" \
    -show_entries frame_tags=lavfi.signalstats.YAVG -of csv=p=0 2>/dev/null) | \
    awk -F, 'NF { sum += $1; n += 1 } END { if (n) printf "%d", sum / n; else print 0 }'
}

# Validate the pending capture $1 against luma threshold $2, publishing to
# $3 only on success (atomic rename: a die or signal before validation can
# never leave a publishable name behind). Quarantine names derive from the
# final path. Threshold depends on the pixel range: PNG screenshots are
# full-range (black = 0, threshold 8); H.264 recordings are video-range
# (black = 16, threshold 20).
gate_dark_and_publish() {
  local pending="$1" threshold="$2" final="$3" luma
  # A failed probe (undecodable file) must quarantine, not abort via set -e
  # with the artifact still on disk unvalidated.
  if ! luma="$(mean_luma "${pending}")" || [[ ! "${luma}" =~ ^[0-9]+$ ]]; then
    mv "${pending}" "${final}.corrupt"
    die "could not measure luma (undecodable capture); kept as ${final}.corrupt"
  fi
  if (( luma < threshold )) && [[ "${ALLOW_DARK}" != "1" ]]; then
    local quarantined="${final%.*}.black.${final##*.}"
    mv "${pending}" "${quarantined}"
    die "capture is near-black (mean luma ${luma}); quarantined as ${quarantined} — pass --allow-dark for legitimately dark content"
  fi
  if (( luma < threshold )); then
    log "near-black capture (mean luma ${luma}) kept: --allow-dark"
  fi
  mv "${pending}" "${final}"
}

case "${cmd}" in
  screenshot)
    out="${EVIDENCE_DIR}/${STAMP}-${LABEL}.png"
    pending="${out}.pending"
    # Guard capture commands: under set -e an adb failure would otherwise
    # exit before cleanup, stranding a partial file.
    if ! "${ADB}" -s "${SERIAL}" exec-out screencap -p > "${pending}"; then
      rm -f "${pending}"
      die "screencap failed on ${SERIAL}"
    fi
    [[ -s "${pending}" ]] || { rm -f "${pending}"; die "screencap produced no data"; }
    # A truncated screencap is not a PNG; check the magic bytes.
    if [[ "$(head -c 4 "${pending}" | xxd -p)" != "89504e47" ]]; then
      mv "${pending}" "${out}.corrupt"
      die "screencap output is not a PNG; kept as ${out}.corrupt"
    fi
    gate_dark_and_publish "${pending}" 8 "${out}"
    log "screenshot: ${out}"
    echo "${out}"
    ;;
  record)
    out="${EVIDENCE_DIR}/${STAMP}-${LABEL}.mp4"
    pending="${out}.pending"
    remote="/data/local/tmp/putio-evidence.mp4"
    # No internal retry: callers that choreograph content during capture
    # (prove.sh relaunches the app mid-recording) would desync from it and
    # publish a clip that missed the action. Callers own whole-cycle retries.
    "${ADB}" -s "${SERIAL}" shell screenrecord --time-limit "${SECONDS_ARG}" "${remote}" || \
      die "screenrecord failed on ${SERIAL} (encoder is briefly unready after boot; retry the capture)"
    # screenrecord can return before the muxer finishes the container; a pull
    # that races it produces an mp4 with no moov atom (unplayable).
    sleep 2
    if ! "${ADB}" -s "${SERIAL}" pull "${remote}" "${pending}" >/dev/null; then
      rm -f "${pending}"
      die "pull of ${remote} failed on ${SERIAL}"
    fi
    "${ADB}" -s "${SERIAL}" shell rm -f "${remote}" || true
    [[ -s "${pending}" ]] || { rm -f "${pending}"; die "screenrecord produced no data"; }
    # Integrity gate on the capture itself, before any trim: a truncated
    # screenrecord container still carries a moov atom but no parseable
    # duration, so ffprobe is the reliable check. A one-frame clip of a
    # static screen parses as ~0.04s; require enough capture to actually
    # show something happening. Trimming may legitimately shorten the
    # published clip below this floor.
    dur="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "${pending}" 2>/dev/null || true)"
    if [[ ! "${dur}" =~ ^[0-9] ]]; then
      mv "${pending}" "${out}.corrupt"
      die "recording is corrupt (no parseable duration); kept as ${out}.corrupt"
    fi
    if (( ${dur%%.*} < 2 )) && [[ "${KEEP_IDLE}" != "1" ]]; then
      # Fall through to normalization: a sub-2s capture of a static screen is
      # idle, not corrupt, and the idle quarantine names the actual problem.
      if normalize_recording "${pending}" "${out}.normalized.pending"; then
        rm -f "${out}.normalized.pending"
        mv "${pending}" "${out}.corrupt"
        die "recording too short (${dur}s — truncated capture); kept as ${out}.corrupt"
      else
        status=$?
        rm -f "${out}.normalized.pending"
        if [[ "${status}" -eq 2 ]]; then
          quarantined="${out}.idle"
          mv "${pending}" "${quarantined}"
          die "recording contains no meaningful motion; quarantined as ${quarantined} — pass --keep-idle for an intentional timing capture"
        fi
        mv "${pending}" "${out}.corrupt"
        die "recording too short (${dur}s — static screen or truncated capture); kept as ${out}.corrupt"
      fi
    fi
    if (( ${dur%%.*} < 2 )); then
      log "short recording (${dur}s) kept: --keep-idle"
    fi
    raw=""
    if [[ "${KEEP_IDLE}" != "1" ]]; then
      normalized="${out}.normalized.pending"
      if normalize_recording "${pending}" "${normalized}"; then
        # Keep the untrimmed capture until publication succeeds so a later
        # gate failure never destroys the only recoverable evidence.
        raw="${out}.raw"
        mv "${pending}" "${raw}"
        pending="${normalized}"
      else
        status=$?
        rm -f "${normalized}"
        if [[ "${status}" -eq 2 ]]; then
          quarantined="${out}.idle"
          mv "${pending}" "${quarantined}"
          die "recording contains no meaningful motion; quarantined as ${quarantined} — pass --keep-idle for an intentional timing capture"
        fi
        mv "${pending}" "${out}.corrupt"
        die "could not normalize recording; kept as ${out}.corrupt"
      fi
    fi
    gate_dark_and_publish "${pending}" 20 "${out}"
    if [[ -n "${raw}" ]]; then rm -f "${raw}"; fi
    log "recording: ${out}"
    echo "${out}"
    ;;
  *)
    die "unknown command: ${cmd} (expected screenshot|record)"
    ;;
esac
