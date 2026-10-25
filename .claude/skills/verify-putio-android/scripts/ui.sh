#!/usr/bin/env bash
# Drive the app on one serial through adb and uiautomator, by visible text and
# content descriptions. Run it in the devbox copy (through devbox.sh run).
#
#   ui.sh dump       <serial> [out.xml]            accessibility tree as XML (stdout without a path)
#   ui.sh find       <serial> <selector> [--last]   print the first (or last) match's centre "x y"; exit 1 if none
#   ui.sh tap        <serial> <selector> [--last]   tap the first (or last) match
#   ui.sh long-press <serial> <selector> [--last]   hold the first (or last) match for 1.2 s
#   ui.sh wait       <serial> <selector> [seconds]  poll until a match shows (default 30 s)
#   ui.sh gone       <serial> <selector> [seconds]  poll until no match shows (default 30 s)
#   ui.sh scroll-to  <serial> <selector> [swipes]   swipe the list up until a match shows (default 12);
#                                                   it starts where the list is, so refresh first
#   ui.sh refresh    <serial>                       scroll the list to its top and pull to refresh
#   ui.sh type       <serial> <text>                type into the focused field
#   ui.sh link       <serial> <uri>                 open a deep link in the app (am start -W -p <package>)
#   ui.sh key        <serial> <BACK|ENTER|...>      press a key
#
# Selectors match the whole text or content-desc of a node:
#   --text 'Search your files'   --desc 'Actions for x'   --any 'Files'   --match '<ERE>'
# Compose test tags are not in the tree (the app does not expose them as
# resource ids); use the strings the user sees.
set -euo pipefail

PACKAGE="${PUTIO_VERIFY_PACKAGE:-io.put.putio.mobile.debug}"
: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
ADB="${ANDROID_HOME}/platform-tools/adb"

die() { echo "ui.sh: $*" >&2; exit 1; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }

cmd="${1:-}"; serial="${2:-}"
[[ -n "${cmd}" && -n "${serial}" ]] || usage
shift 2
adb_() { "${ADB}" -s "${serial}" "$@"; }

# Prints the accessibility tree; fails, printing nothing, when uiautomator
# returns no complete hierarchy (an error line, an idle-state timeout).
dump_xml() {
  local xml
  xml="$(adb_ exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
  [[ "${xml}" == *"<hierarchy"*"</hierarchy>"* ]] || return 1
  # uiautomator appends "UI hierchary dumped to: /dev/tty" after the XML.
  printf '%s\n' "${xml%%</hierarchy>*}</hierarchy>"
}

# Reads XML on stdin; prints "x y" of the first node matching the selector.
locate() {
  python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
kind, value = sys.argv[1], sys.argv[2]
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit(2)
try:
    regex = re.compile(value) if kind == "--match" else None
except re.error:
    sys.exit(64)
def hit(node):
    text, desc = node.get("text", ""), node.get("content-desc", "")
    if kind == "--text": return text == value
    if kind == "--desc": return desc == value
    if kind == "--any": return value in (text, desc)
    if kind == "--match": return bool(regex.search(text) or regex.search(desc))
    sys.exit(64)
matches = [node for node in root.iter("node") if hit(node)]
if not matches:
    sys.exit(1)
node = matches[-1] if sys.argv[3] == "last" else matches[0]
x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
print((x1 + x2) // 2, (y1 + y2) // 2)
' "$1" "$2" "${PICK}"
}

# A trailing --last picks the last match in tree order, e.g. a dialog's confirm
# button that shares its label with the dialog's title.
PICK=first
[[ "${*: -1}" == "--last" ]] && { PICK=last; set -- "${@:1:$(($# - 1))}"; }

selector() {
  [[ $# -ge 2 ]] || usage
  case "$1" in --text|--desc|--any|--match) ;; *) usage ;; esac
}

find_now() { dump_xml | locate "$1" "$2"; }

# locate exits 0 on a match, 1 on a readable tree without one; anything else
# (no tree, bad XML, a bad pattern) is a failed read, never an answer.
poll() {
  local want="$1" kind="$2" value="$3" seconds="$4" deadline status
  deadline=$((SECONDS + seconds))
  while ((SECONDS < deadline)); do
    set +e; find_now "${kind}" "${value}" >/dev/null; status=$?; set -e
    case "${status}" in
      0) [[ "${want}" == "shown" ]] && return 0 ;;
      1) [[ "${want}" == "gone" ]] && return 0 ;;
      64) die "invalid selector ${kind} '${value}'" ;;
    esac
    sleep 1
  done
  die "timed out after ${seconds}s waiting for ${kind} '${value}' to be ${want} (last read status ${status})"
}

case "${cmd}" in
  dump)
    if [[ -n "${1:-}" ]]; then
      dump_xml > "$1" || { rm -f "$1"; die "no accessibility tree from ${serial}"; }
      echo "$1"
    else
      dump_xml || die "no accessibility tree from ${serial}"
    fi
    ;;
  find)
    selector "$@"; find_now "$1" "$2" || die "no node for $1 '$2'"
    ;;
  tap)
    selector "$@"; read -r x y < <(find_now "$1" "$2") || die "no node for $1 '$2'"
    adb_ shell input tap "${x}" "${y}"
    ;;
  long-press)
    selector "$@"; read -r x y < <(find_now "$1" "$2") || die "no node for $1 '$2'"
    # `input swipe` in place does not reach every long-press handler on API 37.
    adb_ shell "input motionevent DOWN ${x} ${y}; sleep 1.2; input motionevent UP ${x} ${y}"
    ;;
  wait)
    selector "$@"; poll shown "$1" "$2" "${3:-30}"
    ;;
  gone)
    selector "$@"; poll gone "$1" "$2" "${3:-30}"
    ;;
  scroll-to)
    selector "$@"
    swipes="${3:-12}"
    read -r width height < <(adb_ shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
    for ((i = 0; i <= swipes; i++)); do
      find_now "$1" "$2" >/dev/null && exit 0
      adb_ shell input swipe $((width / 2)) $((height * 7 / 10)) $((width / 2)) $((height * 3 / 10)) 300
      sleep 1
    done
    die "no node for $1 '$2' after ${swipes} swipes"
    ;;
  refresh)
    read -r width height < <(adb_ shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
    # Scroll back to the top, then pull past the threshold once more.
    for _ in 1 2 3 4 5; do
      adb_ shell input swipe $((width / 2)) $((height * 3 / 10)) $((width / 2)) $((height * 8 / 10)) 150
    done
    sleep 1
    adb_ shell input swipe $((width / 2)) $((height * 3 / 10)) $((width / 2)) $((height * 8 / 10)) 700
    ;;
  type)
    [[ -n "${1:-}" ]] || usage
    [[ "$1" != *[[:cntrl:]]* ]] || die "type takes one line of printable text"
    # `input text` reads %s as a space; the device shell gets the whole
    # argument single-quoted, with any apostrophe closed and escaped.
    quote="'\\''"
    text="${1// /%s}"
    adb_ shell "input text '${text//\'/${quote}}'"
    ;;
  link)
    [[ -n "${1:-}" ]] || usage
    # adb joins its arguments into one device-shell line: single-quote the URI,
    # closing and escaping any apostrophe inside it.
    quote="'\\''"
    uri="${1//\'/${quote}}"
    adb_ shell am start -W -a android.intent.action.VIEW -d "'${uri}'" -p "${PACKAGE}" >/dev/null
    ;;
  key)
    [[ -n "${1:-}" ]] || usage
    adb_ shell input keyevent "KEYCODE_$1"
    ;;
  *) usage ;;
esac
