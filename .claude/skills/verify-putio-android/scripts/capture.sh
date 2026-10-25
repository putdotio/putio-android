#!/usr/bin/env bash
# Capture one proof step: screenshot, accessibility tree and the app's logcat,
# into .evidence/<run>/. Run it in the devbox copy (through devbox.sh run).
#
#   capture.sh <run> <label> [--allow-dark]
#
# Writes .evidence/<run>/<UTC stamp>-<label>.png (validated by scripts/evidence.sh),
# .xml next to it, and .logcat.txt (logcat -d for the app's uid only). Each
# capture is staged in its own directory and scanned for session secrets; only
# a clean capture moves into .evidence/<run>/. A hit, a failure, a rejected
# screenshot or an interruption removes the whole stage. Prints EVIDENCE <path>
# per file kept.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
ADB="${ANDROID_HOME}/platform-tools/adb"

die() { echo "capture.sh: $*" >&2; exit 1; }
run="${1:-}"; label="${2:-}"; allow_dark="${3:-}"
[[ "${run}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ && "${label}" =~ ^[A-Za-z0-9._-]+$ && $# -le 3 &&
  ( -z "${allow_dark}" || "${allow_dark}" == "--allow-dark" ) ]] || die "usage: capture.sh <run> <label> [--allow-dark]"
DIR="${ROOT}/.evidence/${run}"
ENV_FILE="${DIR}/run.env"
[[ -f "${ENV_FILE}" ]] || die "no ${ENV_FILE}; run session.sh up ${run}"
serial="$(sed -n 's/^SERIAL=//p' "${ENV_FILE}" | tail -1)"
package="$(sed -n 's/^PACKAGE=//p' "${ENV_FILE}" | tail -1)"

STAGE="$(mktemp -d "${DIR}/.capture-XXXXXX")"
# Bash 3.2 skips the EXIT trap on a fatal signal, so signals clean up too.
trap 'rm -rf "${STAGE}"' EXIT
trap 'rm -rf "${STAGE}"; trap - EXIT; exit 130' INT TERM HUP

png="$(EVIDENCE_DIR="${STAGE}" "${ROOT}/scripts/evidence.sh" screenshot --serial "${serial}" --label "${label}" ${allow_dark:+--allow-dark})" ||
  die "screenshot rejected as black or corrupt; nothing kept"
stem="${png%.png}"

"$(dirname "${BASH_SOURCE[0]}")/ui.sh" dump "${serial}" "${stem}.xml" >/dev/null

uid="$("${ADB}" -s "${serial}" shell pm list packages -U "${package}" | sed -n "s/^package:${package} uid:\([0-9]*\).*/\1/p" | tr -d '\r')"
[[ -n "${uid}" ]] || die "${package} is not installed on ${serial}"
"${ADB}" -s "${serial}" logcat -d --uid="${uid}" > "${stem}.logcat.txt"

# Token-shaped values in query, header, JSON, escaped-JSON and entity-encoded
# forms; the match is never printed.
python3 -I - "${STAGE}" <<'PY' || die "a capture held a session secret; nothing kept"
import html, os, re, sys, urllib.parse
pattern = re.compile(
    rb"(oauth_?token|access_?token|refresh_?token|download_?token)[\"'\s]*[=:][\"'\s]*[A-Za-z0-9_.~+/-]"
    rb"|[?&;]token=[A-Za-z0-9_.~+/-]"
    rb"|[\"']token[\"']\s*:\s*[\"'][A-Za-z0-9_.~+/-]"
    rb"|authorization[\"'\s]*[=:][\"'\s]*(bearer|token)\s+[A-Za-z0-9_.~+/-]",
    re.IGNORECASE,
)
for name in os.listdir(sys.argv[1]):
    data = open(os.path.join(sys.argv[1], name), "rb").read()
    text = data.decode("utf-8", "replace")
    unescaped = urllib.parse.unquote(html.unescape(text)).encode()
    for form in (data, unescaped, data.replace(b"\\", b""), unescaped.replace(b"\\", b"")):
        if pattern.search(form):
            sys.exit(1)
PY
for file in "${STAGE}"/*; do
  mv "${file}" "${DIR}/"
  echo "EVIDENCE .evidence/${run}/$(basename "${file}")"
done
