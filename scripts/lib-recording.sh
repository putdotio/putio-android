#!/usr/bin/env bash

# Normalize a recording for publication. Sustained frozen lead/tail is cut
# with a little context left around the visible action. Return 2 when the
# whole clip is idle, and 1 for an ffmpeg/ffprobe failure.
normalize_recording() {
  local input="$1" output="$2"
  local LC_ALL=C
  export LC_ALL
  local duration freeze_log freeze_start="" leading_end="" trailing_start=""
  local line end freeze_duration start trim_end trim_duration

  command -v ffmpeg >/dev/null 2>&1 || return 1
  command -v ffprobe >/dev/null 2>&1 || return 1

  duration="$(ffprobe -v error -show_entries format=duration \
    -of default=noprint_wrappers=1:nokey=1 "${input}" 2>/dev/null)" || return 1
  [[ "${duration}" =~ ^[0-9]+([.][0-9]+)?$ ]] || return 1

  freeze_log="$(mktemp)" || return 1
  if ! ffmpeg -hide_banner -nostats -i "${input}" \
    -vf 'freezedetect=n=-80dB:d=0.75' -an -f null - \
    >/dev/null 2>"${freeze_log}"; then
    rm -f "${freeze_log}"
    return 1
  fi

  while IFS= read -r line; do
    if [[ "${line}" =~ lavfi.freezedetect.freeze_start:\ ([0-9]+([.][0-9]+)?) ]]; then
      freeze_start="${BASH_REMATCH[1]}"
      continue
    fi
    if [[ "${line}" =~ lavfi.freezedetect.freeze_end:\ ([0-9]+([.][0-9]+)?) ]]; then
      end="${BASH_REMATCH[1]}"
      if [[ -n "${freeze_start}" ]]; then
        freeze_duration="$(awk -v start="${freeze_start}" -v end="${end}" 'BEGIN { print end - start }')"
        if awk -v start="${freeze_start}" -v span="${freeze_duration}" \
          'BEGIN { exit !(start == 0 && span >= 3) }'; then
          leading_end="${end}"
        fi
      fi
      freeze_start=""
    fi
  done < "${freeze_log}"
  rm -f "${freeze_log}"

  # An open freeze runs through EOF. A clip frozen from its first frame has
  # no publishable action; a late open freeze is removable tail idle.
  if [[ -n "${freeze_start}" ]]; then
    if awk -v start="${freeze_start}" 'BEGIN { exit !(start == 0) }'; then
      return 2
    fi
    if awk -v start="${freeze_start}" -v end="${duration}" \
      'BEGIN { exit !((end - start) >= 3) }'; then
      trailing_start="${freeze_start}"
    fi
  fi

  start=0
  if [[ -n "${leading_end}" ]]; then
    start="$(awk -v end="${leading_end}" 'BEGIN { value = end - 0.75; print (value > 0 ? value : 0) }')"
  fi
  trim_end="${duration}"
  if [[ -n "${trailing_start}" ]]; then
    trim_end="$(awk -v start="${trailing_start}" -v end="${duration}" \
      'BEGIN { value = start + 1.5; print (value < end ? value : end) }')"
  fi

  if awk -v start="${start}" -v end="${trim_end}" -v duration="${duration}" \
    'BEGIN { exit !(start < 0.01 && (duration - end) < 0.01) }'; then
    cp "${input}" "${output}"
    return
  fi

  trim_duration="$(awk -v start="${start}" -v end="${trim_end}" 'BEGIN { print end - start }')"
  ffmpeg -hide_banner -loglevel error -y -ss "${start}" -i "${input}" \
    -t "${trim_duration}" -map 0:v:0 -an -c:v libx264 -crf 18 \
    -pix_fmt yuv420p -movflags +faststart -f mp4 "${output}"
}
