#!/usr/bin/env bash
# Capture one proof step: screenshot, accessibility tree and the app's logcat,
# into .evidence/<run>/. Run it in the devbox copy (through devbox.sh run).
#
#   capture.sh <run> <label> [--allow-dark]
#
# Writes .evidence/<run>/<UTC stamp>-<label>.png (validated by scripts/evidence.sh),
# .xml next to it, and .logcat.txt (logcat -d for the app's uid only). Every
# file is scanned for session secrets before it is kept; a hit, a failure or an
# interruption deletes every file of this capture. Prints EVIDENCE <path> per
# file kept.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
ADB="${ANDROID_HOME}/platform-tools/adb"

die() { echo "capture.sh: $*" >&2; exit 1; }
run="${1:-}"; label="${2:-}"; allow_dark="${3:-}"
[[ "${run}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ && "${label}" =~ ^[A-Za-z0-9._-]+$ ]] || die "usage: capture.sh <run> <label> [--allow-dark]"
DIR="${ROOT}/.evidence/${run}"
ENV_FILE="${DIR}/run.env"
[[ -f "${ENV_FILE}" ]] || die "no ${ENV_FILE}; run session.sh up ${run}"
serial="$(sed -n 's/^SERIAL=//p' "${ENV_FILE}" | tail -1)"
package="$(sed -n 's/^PACKAGE=//p' "${ENV_FILE}" | tail -1)"

# Until the scan passes, every file of this capture is provisional.
files=()
kept=0
discard() { ((kept)) || rm -f ${files[@]+"${files[@]}"}; }
trap discard EXIT

png="$(EVIDENCE_DIR="${DIR}" "${ROOT}/scripts/evidence.sh" screenshot --serial "${serial}" --label "${label}" ${allow_dark:+--allow-dark})" ||
  die "screenshot rejected (black or corrupt); see ${DIR}"
stem="${png%.png}"
files=("${png}" "${stem}.xml" "${stem}.logcat.txt")

"$(dirname "${BASH_SOURCE[0]}")/ui.sh" dump "${serial}" "${stem}.xml" >/dev/null

uid="$("${ADB}" -s "${serial}" shell pm list packages -U "${package}" | sed -n "s/^package:${package} uid:\([0-9]*\).*/\1/p" | tr -d '\r')"
[[ -n "${uid}" ]] || die "${package} is not installed on ${serial}"
"${ADB}" -s "${serial}" logcat -d --uid="${uid}" > "${stem}.logcat.txt"

# Token-shaped values in query, header, JSON and entity-encoded forms; the match is never printed.
python3 -I - "${files[@]}" <<'PY' || die "a capture held a session secret; all of its files were deleted"
import html, re, sys
pattern = re.compile(
    rb"(oauth_token|access_token|refresh_token|download_token)[\"'\s]*[=:][\"'\s]*[A-Za-z0-9]"
    rb"|authorization[\"'\s]*[=:][\"'\s]*(bearer|token)\s+[A-Za-z0-9]",
    re.IGNORECASE,
)
for path in sys.argv[1:]:
    data = open(path, "rb").read()
    if pattern.search(data) or pattern.search(html.unescape(data.decode("utf-8", "replace")).encode()):
        sys.exit(1)
PY
kept=1
for file in "${files[@]}"; do echo "EVIDENCE ${file#"${ROOT}/"}"; done
