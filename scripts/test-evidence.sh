#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib-recording.sh"

command -v ffmpeg >/dev/null 2>&1 || { echo "ffmpeg required" >&2; exit 1; }
command -v ffprobe >/dev/null 2>&1 || { echo "ffprobe required" >&2; exit 1; }
ffmpeg_filters="$(ffmpeg -hide_banner -filters 2>/dev/null)"
grep -q ' freezedetect ' <<<"${ffmpeg_filters}" || { echo "ffmpeg freezedetect filter required" >&2; exit 1; }
ffmpeg_encoders="$(ffmpeg -hide_banner -encoders 2>/dev/null)"
grep -q ' libx264 ' <<<"${ffmpeg_encoders}" || { echo "ffmpeg libx264 encoder required" >&2; exit 1; }

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

duration() {
  ffprobe -v error -show_entries format=duration \
    -of default=noprint_wrappers=1:nokey=1 "$1"
}

ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'color=c=gray:s=320x240:r=30:d=4' \
  -f lavfi -i 'testsrc2=s=320x240:r=30:d=2' \
  -f lavfi -i 'color=c=blue:s=320x240:r=30:d=4' \
  -filter_complex '[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]' -map '[v]' \
  -c:v libx264 -pix_fmt yuv420p "${tmpdir}/idle-edges.mp4"
normalize_recording "${tmpdir}/idle-edges.mp4" "${tmpdir}/idle-edges.mp4.normalized.pending"
normalized_duration="$(duration "${tmpdir}/idle-edges.mp4.normalized.pending")"
awk -v value="${normalized_duration}" \
  'BEGIN { exit !(value >= 3 && value <= 5) }' || {
  echo "expected idle-edge clip to normalize to 3-5s, got ${normalized_duration}s" >&2
  exit 1
}

locale -a | grep -Ei '^de_DE[.]utf-?8$' >/dev/null || {
  echo "de_DE.UTF-8 locale required; run scripts/bootstrap.sh" >&2
  exit 1
}
LC_ALL=de_DE.UTF-8 normalize_recording \
  "${tmpdir}/idle-edges.mp4" "${tmpdir}/idle-edges-locale-normalized.mp4"
locale_duration="$(duration "${tmpdir}/idle-edges-locale-normalized.mp4")"
awk -v value="${locale_duration}" 'BEGIN { exit !(value >= 3 && value <= 5) }' || {
  echo "expected locale-safe normalization to produce 3-5s, got ${locale_duration}s" >&2
  exit 1
}

ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'color=c=gray:s=320x240:r=30:d=6' -c:v libx264 -pix_fmt yuv420p \
  "${tmpdir}/static.mp4"
if normalize_recording "${tmpdir}/static.mp4" "${tmpdir}/static-normalized.mp4"; then
  echo "expected a fully static clip to fail normalization" >&2
  exit 1
else
  status=$?
fi
[[ "${status}" -eq 2 ]] || { echo "expected idle status 2, got ${status}" >&2; exit 1; }
[[ ! -e "${tmpdir}/static-normalized.mp4" ]] || {
  echo "static normalization left a publishable output" >&2
  exit 1
}

ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'color=c=gray:s=320x240:r=30:d=2' -c:v libx264 -pix_fmt yuv420p \
  "${tmpdir}/short-static.mp4"
if normalize_recording "${tmpdir}/short-static.mp4" "${tmpdir}/short-static-normalized.mp4"; then
  echo "expected a short static clip to fail normalization" >&2
  exit 1
else
  status=$?
fi
[[ "${status}" -eq 2 ]] || { echo "expected short-idle status 2, got ${status}" >&2; exit 1; }

# An early interior freeze is not leading idle: motion on either side must be
# preserved, even when the pause starts inside the old three-second window.
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=s=320x240:r=30:d=2' \
  -f lavfi -i 'color=c=gray:s=320x240:r=30:d=4' \
  -f lavfi -i 'testsrc2=s=320x240:r=30:d=2' \
  -filter_complex '[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]' -map '[v]' \
  -c:v libx264 -pix_fmt yuv420p "${tmpdir}/interior-freeze.mp4"
normalize_recording "${tmpdir}/interior-freeze.mp4" "${tmpdir}/interior-freeze-normalized.mp4"
interior_duration="$(duration "${tmpdir}/interior-freeze-normalized.mp4")"
awk -v value="${interior_duration}" 'BEGIN { exit !(value >= 7.9 && value <= 8.1) }' || {
  echo "expected interior freeze clip to preserve all 8s, got ${interior_duration}s" >&2
  exit 1
}

# A short visible action is still action. The caller's existing minimum-
# duration integrity policy may reject it, but normalization must not call it idle.
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'color=c=gray:s=320x240:r=30:d=4' \
  -f lavfi -i 'testsrc2=s=320x240:r=30:d=0.5' \
  -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' \
  -c:v libx264 -pix_fmt yuv420p "${tmpdir}/short-action.mp4"
normalize_recording "${tmpdir}/short-action.mp4" "${tmpdir}/short-action-normalized.mp4"
short_action_duration="$(duration "${tmpdir}/short-action-normalized.mp4")"
awk -v value="${short_action_duration}" 'BEGIN { exit !(value >= 1.2 && value <= 1.4) }' || {
  echo "expected short action to survive normalization, got ${short_action_duration}s" >&2
  exit 1
}

# Motion before an open freeze, even in the first 250ms, is evidence.
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=s=320x240:r=30:d=0.2' \
  -f lavfi -i 'color=c=gray:s=320x240:r=30:d=4' \
  -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' \
  -c:v libx264 -pix_fmt yuv420p "${tmpdir}/early-open-action.mp4"
normalize_recording "${tmpdir}/early-open-action.mp4" "${tmpdir}/early-open-action-normalized.mp4"
early_open_duration="$(duration "${tmpdir}/early-open-action-normalized.mp4")"
awk -v value="${early_open_duration}" 'BEGIN { exit !(value >= 1.6 && value <= 1.8) }' || {
  echo "expected early action before open freeze to survive, got ${early_open_duration}s" >&2
  exit 1
}

# Motion before a closed freeze must not be cropped when later motion exists.
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=s=320x240:r=30:d=0.2' \
  -f lavfi -i 'color=c=gray:s=320x240:r=30:d=4' \
  -f lavfi -i 'testsrc2=s=320x240:r=30:d=0.5' \
  -filter_complex '[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]' -map '[v]' \
  -c:v libx264 -pix_fmt yuv420p "${tmpdir}/early-closed-action.mp4"
normalize_recording "${tmpdir}/early-closed-action.mp4" "${tmpdir}/early-closed-action-normalized.mp4"
early_closed_duration="$(duration "${tmpdir}/early-closed-action-normalized.mp4")"
awk -v value="${early_closed_duration}" 'BEGIN { exit !(value >= 4.6 && value <= 4.8) }' || {
  echo "expected action around closed freeze to preserve all 4.7s, got ${early_closed_duration}s" >&2
  exit 1
}

# A small Android-sized control change must break a global freeze. Use the
# same 48x48 region cited by review in a 1920x1080 frame.
ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'color=c=black:s=1920x1080:r=10:d=4' \
  -vf "drawbox=x=100:y=100:w=48:h=48:color=white:t=fill:enable='between(t,1,1.2)'" \
  -c:v libx264 -pix_fmt yuv420p "${tmpdir}/localized-action.mp4"
normalize_recording "${tmpdir}/localized-action.mp4" "${tmpdir}/localized-action-normalized.mp4"
localized_duration="$(duration "${tmpdir}/localized-action-normalized.mp4")"
awk -v value="${localized_duration}" 'BEGIN { exit !(value >= 3.9 && value <= 4.1) }' || {
  echo "expected localized action to preserve the 4s clip, got ${localized_duration}s" >&2
  exit 1
}

ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'testsrc2=s=320x240:r=30:d=5' -c:v libx264 -pix_fmt yuv420p \
  "${tmpdir}/active.mp4"
normalize_recording "${tmpdir}/active.mp4" "${tmpdir}/active-normalized.mp4"
active_duration="$(duration "${tmpdir}/active-normalized.mp4")"
awk -v value="${active_duration}" 'BEGIN { exit !(value >= 4.9 && value <= 5.1) }' || {
  echo "expected active clip to remain 5s, got ${active_duration}s" >&2
  exit 1
}

# Exercise evidence.sh's publication boundary with adb stubbed at the SDK
# boundary. The real ffmpeg pipeline still classifies and validates fixtures.
fake_sdk="${tmpdir}/android-sdk"
mkdir -p "${fake_sdk}/platform-tools"
cat > "${fake_sdk}/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *" shell screenrecord "*) exit 0 ;;
  *" pull "*) cp "${ADB_FIXTURE:?}" "${!#}" ;;
  *" shell rm -f "*) exit 0 ;;
  *) echo "unexpected adb call: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "${fake_sdk}/platform-tools/adb"

static_gate_dir="${tmpdir}/static-gate"
mkdir "${static_gate_dir}"
if ANDROID_HOME="${fake_sdk}" EVIDENCE_DIR="${static_gate_dir}" ADB_FIXTURE="${tmpdir}/static.mp4" \
  "${BASH_SOURCE[0]%/*}/evidence.sh" record --serial test --seconds 6 >/dev/null 2>&1; then
  echo "expected evidence.sh to reject a static recording" >&2
  exit 1
fi
idle_files=("${static_gate_dir}"/*.idle.mp4)
[[ "${#idle_files[@]}" -eq 1 && -f "${idle_files[0]}" ]] || {
  echo "expected exactly one quarantined idle recording" >&2
  exit 1
}
publishable_count="$(find "${static_gate_dir}" -maxdepth 1 -type f -name '*.mp4' ! -name '*.idle.mp4' | wc -l | tr -d ' ')"
[[ "${publishable_count}" -eq 0 ]] || {
  echo "static recording left a publishable output" >&2
  exit 1
}

corrupt_fixture="${tmpdir}/invalid.mp4"
printf 'not an mp4' > "${corrupt_fixture}"
corrupt_gate_dir="${tmpdir}/corrupt-gate"
mkdir "${corrupt_gate_dir}"
if ANDROID_HOME="${fake_sdk}" EVIDENCE_DIR="${corrupt_gate_dir}" ADB_FIXTURE="${corrupt_fixture}" \
  "${BASH_SOURCE[0]%/*}/evidence.sh" record --serial test --seconds 6 >/dev/null 2>&1; then
  echo "expected evidence.sh to reject a normalization failure" >&2
  exit 1
fi
corrupt_files=("${corrupt_gate_dir}"/*.corrupt)
[[ "${#corrupt_files[@]}" -eq 1 && -f "${corrupt_files[0]}" ]] || {
  echo "expected exactly one corrupt quarantine" >&2
  exit 1
}

normalized_gate_dir="${tmpdir}/normalized-gate"
mkdir "${normalized_gate_dir}"
normalized_path="$(ANDROID_HOME="${fake_sdk}" EVIDENCE_DIR="${normalized_gate_dir}" ADB_FIXTURE="${tmpdir}/idle-edges.mp4" \
  "${BASH_SOURCE[0]%/*}/evidence.sh" record --serial test --seconds 10 2>/dev/null)"
[[ -f "${normalized_path}" ]] || {
  echo "expected the default record path to publish normalized output" >&2
  exit 1
}
caller_duration="$(duration "${normalized_path}")"
awk -v value="${caller_duration}" 'BEGIN { exit !(value >= 3 && value <= 5) }' || {
  echo "expected caller-normalized output to be 3-5s, got ${caller_duration}s" >&2
  exit 1
}
pending_count="$(find "${normalized_gate_dir}" -maxdepth 1 -type f -name '*.pending' | wc -l | tr -d ' ')"
[[ "${pending_count}" -eq 0 ]] || {
  echo "default normalized publication left pending artifacts" >&2
  exit 1
}

short_gate_dir="${tmpdir}/short-gate"
mkdir "${short_gate_dir}"
if ANDROID_HOME="${fake_sdk}" EVIDENCE_DIR="${short_gate_dir}" ADB_FIXTURE="${tmpdir}/short-action.mp4" \
  "${BASH_SOURCE[0]%/*}/evidence.sh" record --serial test --seconds 4 >/dev/null 2>&1; then
  echo "expected post-normalization duration gate to reject a short recording" >&2
  exit 1
fi
short_corrupt_files=("${short_gate_dir}"/*.corrupt)
[[ "${#short_corrupt_files[@]}" -eq 1 && -f "${short_corrupt_files[0]}" ]] || {
  echo "expected normalized short recording to be quarantined as corrupt" >&2
  exit 1
}
short_publishable_count="$(find "${short_gate_dir}" -maxdepth 1 -type f -name '*.mp4' | wc -l | tr -d ' ')"
[[ "${short_publishable_count}" -eq 0 ]] || {
  echo "normalized short recording left a publishable output" >&2
  exit 1
}

ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'color=c=gray:s=320x240:r=30' -frames:v 1 -c:v libx264 -pix_fmt yuv420p \
  "${tmpdir}/one-frame-static.mp4"

one_frame_idle_dir="${tmpdir}/one-frame-idle"
mkdir "${one_frame_idle_dir}"
if ANDROID_HOME="${fake_sdk}" EVIDENCE_DIR="${one_frame_idle_dir}" ADB_FIXTURE="${tmpdir}/one-frame-static.mp4" \
  "${BASH_SOURCE[0]%/*}/evidence.sh" record --serial test --seconds 3 >/dev/null 2>&1; then
  echo "expected a one-frame static recording to fail as idle" >&2
  exit 1
fi
one_frame_idle_files=("${one_frame_idle_dir}"/*.idle.mp4)
[[ "${#one_frame_idle_files[@]}" -eq 1 && -f "${one_frame_idle_files[0]}" ]] || {
  echo "expected one-frame static recording to use the idle quarantine" >&2
  exit 1
}

keep_idle_dir="${tmpdir}/keep-idle"
mkdir "${keep_idle_dir}"
kept_path="$(ANDROID_HOME="${fake_sdk}" EVIDENCE_DIR="${keep_idle_dir}" ADB_FIXTURE="${tmpdir}/one-frame-static.mp4" \
  "${BASH_SOURCE[0]%/*}/evidence.sh" record --serial test --seconds 3 --keep-idle 2>/dev/null)"
[[ -f "${kept_path}" && "${kept_path}" == "${keep_idle_dir}/"*.mp4 ]] || {
  echo "expected --keep-idle to publish the intentional static recording" >&2
  exit 1
}
kept_duration="$(duration "${kept_path}")"
awk -v value="${kept_duration}" 'BEGIN { exit !(value < 1) }' || {
  echo "expected --keep-idle caller fixture to remain under 1s, got ${kept_duration}s" >&2
  exit 1
}

echo "evidence recording tests passed"
