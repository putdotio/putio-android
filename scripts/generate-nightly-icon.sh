#!/usr/bin/env bash
# Regenerates the nightly launcher icon densities from the putio-design
# source art (app-icon-nightly-stars.png, 1024x1024).
#
# The starfield is baked into the source art: always downscale with a smooth
# filter (sips uses Lanczos-class resampling), never nearest-neighbour, and
# never re-render or re-randomise the stars.
#
# Usage: scripts/generate-nightly-icon.sh [path-to-source-png]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="${1:-${REPO_ROOT}/../putio-design/system/assets/app-icon-nightly-stars.png}"
RES_ROOT="${REPO_ROOT}/app/src/nightly/res"

[[ -f "${SOURCE}" ]] || { echo "source art not found: ${SOURCE}" >&2; exit 1; }
command -v sips >/dev/null || { echo "sips not found (macOS only)" >&2; exit 1; }

# density -> launcher icon size in px
DENSITIES=(
  "mdpi 48"
  "hdpi 72"
  "xhdpi 96"
  "xxhdpi 144"
  "xxxhdpi 192"
)

for entry in "${DENSITIES[@]}"; do
  density="${entry%% *}"
  size="${entry##* }"
  out_dir="${RES_ROOT}/drawable-${density}"
  mkdir -p "${out_dir}"
  sips -s format png -z "${size}" "${size}" "${SOURCE}" \
    --out "${out_dir}/putio_icon.png" >/dev/null
  echo "wrote drawable-${density}/putio_icon.png (${size}px)"
done
