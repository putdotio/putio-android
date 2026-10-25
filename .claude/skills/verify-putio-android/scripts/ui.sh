#!/usr/bin/env bash
# Drive the app on one serial through adb and uiautomator, by visible text and
# content descriptions. Run it in the devbox copy (through devbox.sh run).
#
#   ui.sh dump       <serial> [out.xml]            accessibility tree as XML (stdout without a path)
#   ui.sh find       <serial> <selector>            print the first match's centre "x y"; exit 1 if none
#   ui.sh tap        <serial> <selector>            tap the first match
#   ui.sh long-press <serial> <selector>            hold the first match for 1.2 s
#   ui.sh wait       <serial> <selector> [seconds]  poll until a match shows (default 30 s)
#   ui.sh gone       <serial> <selector> [seconds]  poll until no match shows (default 30 s)
#   ui.sh scroll-to  <serial> <selector> [swipes]   swipe the list up until a match shows (default 12)
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

dump_xml() {
  local xml
  xml="$(adb_ exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
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
def hit(node):
    text, desc = node.get("text", ""), node.get("content-desc", "")
    if kind == "--text": return text == value
    if kind == "--desc": return desc == value
    if kind == "--any": return value in (text, desc)
    if kind == "--match": return bool(re.search(value, text) or re.search(value, desc))
    sys.exit(64)
for node in root.iter("node"):
    if hit(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
' "$1" "$2"
}

selector() {
  [[ $# -ge 2 ]] || usage
  case "$1" in --text|--desc|--any|--match) ;; *) usage ;; esac
}

find_now() { dump_xml | locate "$1" "$2"; }

poll() {
  local want="$1" kind="$2" value="$3" seconds="$4" deadline
  deadline=$((SECONDS + seconds))
  while ((SECONDS < deadline)); do
    if find_now "${kind}" "${value}" >/dev/null; then
      [[ "${want}" == "shown" ]] && return 0
    else
      [[ "${want}" == "gone" ]] && return 0
    fi
    sleep 1
  done
  die "timed out after ${seconds}s waiting for ${kind} '${value}' to be ${want}"
}

case "${cmd}" in
  dump)
    if [[ -n "${1:-}" ]]; then dump_xml > "$1"; echo "$1"; else dump_xml; fi
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
  type)
    [[ -n "${1:-}" ]] || usage
    # `input text` takes %s for a space and needs shell metacharacters escaped.
    escaped="$(printf '%s' "$1" | sed -e 's/[][(){}<>|&;$*?~`"'"'"'\\]/\\&/g' -e 's/ /%s/g')"
    adb_ shell input text "${escaped}"
    ;;
  link)
    [[ -n "${1:-}" ]] || usage
    adb_ shell am start -W -a android.intent.action.VIEW -d "'$1'" -p "${PACKAGE}" >/dev/null
    ;;
  key)
    [[ -n "${1:-}" ]] || usage
    adb_ shell input keyevent "KEYCODE_$1"
    ;;
  *) usage ;;
esac
