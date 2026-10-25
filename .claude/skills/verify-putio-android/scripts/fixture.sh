#!/usr/bin/env bash
# Live fixtures on the shared devs-auto account, owned through a ledger.
# Run it in the devbox copy (through devbox.sh run); needs the putio CLI.
#
#   fixture.sh root    <run>                         create folder android-verify-<run> at the root; print its id
#   fixture.sh mkdir   <run> <parent-id> <name>      create a folder inside an owned folder; print its id
#   fixture.sh upload  <run> <parent-id> <path> [name]  upload a local file into an owned folder; print its id
#   fixture.sh adopt   <run> <id>                    record an item the app made or changed (a new folder,
#                                                    a copy, a rename, a move) so cleanup finds it as it is now
#   fixture.sh owned   <run>                         print "id kind name" for every owned item
#   fixture.sh cleanup <run>                         delete owned items newest first, then prove each is 404
#
# The ledger is .evidence/<run>/fixtures.jsonl: an intent line before each
# write, then its outcome. Cleanup touches only ledger ids. Before deleting
# anything it checks every live id's name and parent against the ledger and
# every owned folder's contents against the ledger, and deletes nothing on a
# mismatch. It deletes with skip_trash so nothing lands in Trash, and keeps the
# ledger as proof. An unknown outcome stops cleanup for read-only
# reconciliation; never repeat it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
PROFILE="devs-auto"

die() { echo "fixture.sh: $*" >&2; exit 1; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }

cmd="${1:-}"; run="${2:-}"
[[ -n "${cmd}" && "${run}" =~ ^[A-Za-z0-9._-]+$ ]] || usage
LEDGER="${ROOT}/.evidence/${run}/fixtures.jsonl"
mkdir -p "$(dirname "${LEDGER}")"

# The profile alone decides the identity; an ambient token would override it.
cli() { env -u PUTIO_CLI_TOKEN PUTIO_CLI_PROFILE="${PROFILE}" putio "$@"; }

py() { python3 -I -c "$@"; }

record() { py 'import json,sys; print(json.dumps(dict(a.split("=",1) for a in sys.argv[1:])))' "$@" >> "${LEDGER}"; }

# "id kind parent name" for each owned item in first-created order, with the
# name and parent its latest created/adopted row records.
created() {
  [[ -f "${LEDGER}" ]] || return 0
  py '
import json, sys
items = {}
for line in open(sys.argv[1]):
    row = json.loads(line)
    if row.get("state") in ("created", "adopted"):
        items[row["id"]] = row
for row in items.values():
    print(row["id"], row["kind"], row["parent"], row["name"])
' "${LEDGER}"
}

require_owned_parent() {
  created | awk -v id="$1" '$1 == id && $2 == "FOLDER" { found = 1 } END { exit !found }' ||
    die "parent $1 is not a folder this run created"
}

preflight() {
  command -v putio >/dev/null || die "putio CLI not on PATH"
  local status
  status="$(env -u PUTIO_CLI_TOKEN putio auth status --profile "${PROFILE}" --output json)" ||
    die "putio auth status failed; run putio auth login --profile ${PROFILE}"
  py 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("authenticated") and d.get("profile")=="devs-auto" and d.get("source")=="profile" else 1)' <<<"${status}" ||
    die "putio profile ${PROFILE} is not signed in"
}

# Runs a write and records its outcome; prints the new id.
write_item() {
  local op="$1" name="$2" parent="$3" kind="$4"; shift 4
  record op="${op}" name="${name}" parent="${parent}" kind="${kind}" state=intent
  local out id
  if ! out="$(cli "$@" --output json 2>&1)"; then
    record op="${op}" name="${name}" parent="${parent}" kind="${kind}" state=unknown
    die "${op} ${name}: outcome unknown; reconcile read-only before any retry"
  fi
  id="$(py 'import json,sys; d=json.load(sys.stdin); d=d.get("file", d); print(d["id"])' <<<"${out}" 2>/dev/null)" || {
    record op="${op}" name="${name}" parent="${parent}" kind="${kind}" state=unknown
    die "${op} ${name}: no id in the response; reconcile read-only before any retry"
  }
  [[ "${kind}" == "FILE" ]] && kind="$(py 'import json,sys; d=json.load(sys.stdin); d=d.get("file", d); print(d.get("file_type","FILE"))' <<<"${out}")"
  record op="${op}" id="${id}" name="${name}" parent="${parent}" kind="${kind}" state=created
  echo "${id}"
}

# Exit 0 when files.get answers exactly 404, 1 when it exists, 2 otherwise.
probe() {
  local out
  out="$(cli sdk call --operation files.get --args "[{\"id\":$1}]" --execute --output json 2>&1 || true)"
  py '
import json, sys
try:
    d = json.loads(sys.stdin.read())
except ValueError:
    sys.exit(2)
if d.get("error", {}).get("httpStatusCode") == 404: sys.exit(0)
r = d.get("result", {})
if r.get("id") == int(sys.argv[1]) and r.get("name") == sys.argv[2] and r.get("parent_id") == int(sys.argv[3]): sys.exit(1)
sys.exit(2)
' "$1" "$2" "$3" <<<"${out}"
}

# The delete can take a moment to show; ten one-second reads bound the wait.
await_404() {
  local _
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    probe "$@" && return 0
    sleep 1
  done
  return 1
}

case "${cmd}" in
  root)
    preflight
    [[ -z "$(created)" ]] || die "run ${run} already owns a root; use another run name or clean up first"
    name="android-verify-${run}"
    write_item mkdir "${name}" 0 FOLDER files mkdir --name "${name}" --parent-id 0
    ;;
  mkdir)
    preflight; parent="${3:?parent id}"; name="${4:?name}"
    require_owned_parent "${parent}"
    write_item mkdir "${name}" "${parent}" FOLDER files mkdir --name "${name}" --parent-id "${parent}"
    ;;
  upload)
    preflight; parent="${3:?parent id}"; path="${4:?local path}"; name="${5:-$(basename "${path}")}"
    [[ -f "${path}" ]] || die "no file ${path}"
    require_owned_parent "${parent}"
    write_item upload "${name}" "${parent}" FILE files upload --parent-id "${parent}" --path "${path}" --file-name "${name}"
    ;;
  adopt)
    preflight; id="${3:?item id}"
    [[ "${id}" =~ ^[1-9][0-9]*$ ]] || die "invalid id '${id}'"
    out="$(cli sdk call --operation files.get --args "[{\"id\":${id}}]" --execute --output json)" || die "files.get ${id} failed"
    read -r parent kind name < <(py 'import json,sys; r=json.load(sys.stdin)["result"]; print(r["parent_id"], r["file_type"], r["name"])' <<<"${out}") ||
      die "files.get ${id}: unexpected response"
    owned_ids="$(created | awk '{ print $1 }')"
    # An item already owned may have moved anywhere the app put it; a new one must sit in an owned folder.
    grep -qx "${id}" <<<"${owned_ids}" || require_owned_parent "${parent}"
    record op=adopt id="${id}" name="${name}" parent="${parent}" kind="${kind}" state=adopted
    echo "adopted ${id} ${kind} ${name} (parent ${parent})"
    ;;
  owned)
    created | awk '{ id = $1; kind = $2; $1 = $2 = $3 = ""; sub(/^ +/, ""); print id, kind, $0 }'
    ;;
  cleanup)
    preflight
    if grep -q '"state": "unknown"' "${LEDGER}" 2>/dev/null; then
      die "the ledger holds an unknown outcome; reconcile it read-only first"
    fi
    owned_ids="$(created | awk '{ print $1 }')"
    # Check everything before deleting anything: each live id must still be the
    # item the ledger names, and each live folder must hold only owned ids.
    pending=()
    while read -r id kind parent name; do
      [[ "${id}" =~ ^[1-9][0-9]*$ ]] || die "refusing id '${id}'"
      if probe "${id}" "${name}" "${parent}"; then
        echo "gone ${id} ${name}"; continue
      elif [[ $? -eq 2 ]]; then
        die "${id} is no longer '${name}' in ${parent}; adopt it if the app changed it, else reconcile read-only. Nothing deleted"
      fi
      if [[ "${kind}" == "FOLDER" ]]; then
        children="$(cli files list --parent-id "${id}" --per-page 100 --page-all --output json |
          py 'import json,sys; [print(f["id"]) for f in json.load(sys.stdin)["files"]]')" ||
          die "could not list folder ${id}; nothing deleted"
        for child in ${children}; do
          grep -qx "${child}" <<<"${owned_ids}" ||
            die "folder ${id} holds ${child}, which the ledger does not own; adopt it or reconcile. Nothing deleted"
        done
      fi
      pending+=("${id}|${name}|${parent}")
    done < <(created | tail -r)
    status=0
    # Newest first, so children go before their parents.
    for item in ${pending[@]+"${pending[@]}"}; do
      IFS='|' read -r id name parent <<<"${item}"
      if probe "${id}" "${name}" "${parent}"; then
        echo "gone ${id} ${name} (removed with its folder)"; continue
      fi
      record op=delete id="${id}" name="${name}" state=intent
      if cli files delete --id "${id}" --skip-trash --output json >/dev/null 2>&1 && await_404 "${id}" "${name}" "${parent}"; then
        record op=delete id="${id}" name="${name}" state=deleted
        echo "deleted ${id} ${name} (404 confirmed)"
      else
        record op=delete id="${id}" name="${name}" state=unknown
        echo "fixture.sh: delete ${id} unconfirmed; reconcile read-only" >&2; status=1
      fi
    done
    exit "${status}"
    ;;
  *) usage ;;
esac
