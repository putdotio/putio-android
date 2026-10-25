#!/usr/bin/env bash
# Capture one proof step: screenshot, accessibility tree and the app's logcat,
# into .evidence/<run>/. Run it in the devbox copy (through devbox.sh run).
#
#   capture.sh <run> <label> [--allow-dark]
#
# Writes .evidence/<run>/<UTC stamp>-<label>.png (validated by scripts/evidence.sh),
# .xml next to it, and .logcat.txt (logcat -d for the app's uid only). Every
# file is scanned for session secrets; a hit deletes that file and fails.
# Prints EVIDENCE <path> per file kept.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
ADB="${ANDROID_HOME}/platform-tools/adb"

die() { echo "capture.sh: $*" >&2; exit 1; }
run="${1:-}"; label="${2:-}"; allow_dark="${3:-}"
[[ "${run}" =~ ^[A-Za-z0-9._-]+$ && "${label}" =~ ^[A-Za-z0-9._-]+$ ]] || die "usage: capture.sh <run> <label> [--allow-dark]"
DIR="${ROOT}/.evidence/${run}"
ENV_FILE="${DIR}/run.env"
[[ -f "${ENV_FILE}" ]] || die "no ${ENV_FILE}; run session.sh up ${run}"
serial="$(sed -n 's/^SERIAL=//p' "${ENV_FILE}" | tail -1)"
package="$(sed -n 's/^PACKAGE=//p' "${ENV_FILE}" | tail -1)"

png="$(EVIDENCE_DIR="${DIR}" "${ROOT}/scripts/evidence.sh" screenshot --serial "${serial}" --label "${label}" ${allow_dark:+--allow-dark})" ||
  die "screenshot rejected (black or corrupt); see ${DIR}"
stem="${png%.png}"

"$(dirname "${BASH_SOURCE[0]}")/ui.sh" dump "${serial}" "${stem}.xml" >/dev/null

uid="$("${ADB}" -s "${serial}" shell pm list packages -U "${package}" | sed -n "s/^package:${package} uid:\([0-9]*\).*/\1/p" | tr -d '\r')"
[[ -n "${uid}" ]] || die "${package} is not installed on ${serial}"
"${ADB}" -s "${serial}" logcat -d --uid="${uid}" > "${stem}.logcat.txt"

status=0
for file in "${png}" "${stem}.xml" "${stem}.logcat.txt"; do
  # Token-shaped values only; the match itself is never printed.
  if LC_ALL=C grep -aqiE 'oauth_token=[A-Za-z0-9]|access_token["=: ]+[A-Za-z0-9]|authorization: *(bearer|token) [A-Za-z0-9]' "${file}"; then
    rm -f "${file}"
    echo "capture.sh: removed ${file}: it held a session secret" >&2
    status=1
  else
    echo "EVIDENCE ${file#"${ROOT}/"}"
  fi
done
exit "${status}"
