#!/usr/bin/env bash
# Live fixtures on the shared devs-auto account, owned through a ledger.
# Run it from the repository root; needs the putio CLI.
#
#   fixture.sh root    <run>                            create folder android-verify-<run> at the root; print its id
#   fixture.sh mkdir   <run> <parent-id> <name>         create a folder inside an owned folder; print its id
#   fixture.sh upload  <run> <parent-id> <path> [name]  upload a local file into an owned folder; print its id
#   fixture.sh intend  <run> <what>                     record a UI mutation before doing it ("rename <id>")
#   fixture.sh adopt   <run> <id>                       record an item the app made or changed (a new folder,
#                                                       a copy, a rename, a move) so cleanup finds it as it is now
#   fixture.sh settle  <run> <what>                     close the open intent or unknown outcome once every
#                                                       affected item is adopted or accounted for
#                                                       ("renamed", "cancelled", "trashed <id>, removed from Trash")
#   fixture.sh owned   <run>                            print "id kind name" for every owned item
#   fixture.sh cleanup <run>                            delete owned items deepest first, then prove each is 404
#
# The ledger is .evidence/<run>/fixtures.jsonl. Every write, by this script or
# through the UI, gets an intent line first and an outcome after it. While an
# intent is open or an outcome is unknown, every write and cleanup refuses:
# reconcile read-only, adopt what exists, then settle; never repeat the write. Before
# deleting anything, cleanup checks each live id's name and parent and each
# owned folder's contents against the ledger, and deletes nothing on a
# mismatch. It deletes with skip_trash so nothing lands in Trash, stops at the
# first unconfirmed delete, and keeps the ledger as proof.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
PROFILE="devs-auto"
API_BASE_URL="https://api.put.io"

die() { echo "fixture.sh: $*" >&2; exit 1; }
usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}" >&2; exit 64; }

cmd="${1:-}"; run="${2:-}"
[[ -n "${cmd}" && "${run}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage
LEDGER="${ROOT}/.evidence/${run}/fixtures.jsonl"
mkdir -p "$(dirname "${LEDGER}")"
touch "${LEDGER}"

# One fixture command per run at a time, so two writes cannot both pass the
# settled check before either records its intent.
LOCK="${LEDGER}.lock"
locked=0
for _ in $(seq 30); do mkdir "${LOCK}" 2>/dev/null && { locked=1; break; }; sleep 1; done
((locked)) || die "another fixture.sh holds ${LOCK}; if none runs, remove that directory"
trap 'rm -rf "${LOCK}"' EXIT
trap 'rm -rf "${LOCK}"; trap - EXIT; exit 130' INT TERM HUP

# The profile alone decides the identity; an ambient token would override it.
cli() { env -u PUTIO_CLI_TOKEN PUTIO_CLI_PROFILE="${PROFILE}" putio "$@"; }

record() { python3 -I -c 'import json,sys; print(json.dumps(dict(a.split("=",1) for a in sys.argv[1:])))' "$@" >> "${LEDGER}"; }

# Ledger queries; see the case list at the bottom of the program.
ledger() {
  python3 -I - "${LEDGER}" "$@" <<'PY'
import json, sys
path, query, *args = sys.argv[1:]
rows = [json.loads(line) for line in open(path) if line.strip()]
items = {}
for row in rows:
    if row.get("state") in ("created", "adopted"):
        items[row["id"]] = row
if query == "settled":
    # An intent or an unknown outcome stays open until its write is created
    # or deleted, or until settle closes it; adopt records items without
    # closing, so a bulk action stays open until every item is adopted.
    open_op = False
    for row in rows:
        state = row.get("state")
        if state in ("intent", "unknown"):
            open_op = True
        elif state in ("created", "deleted", "settled"):
            open_op = False
    sys.exit(1 if open_op else 0)
if query == "account":
    accounts = [r["id"] for r in rows if r.get("op") == "account"]
    print(accounts[-1] if accounts else "")
if query == "owned":
    for row in items.values():
        print(row["id"], row["kind"], row["name"])
if query == "is-folder":
    sys.exit(0 if items.get(args[0], {}).get("kind") == "FOLDER" else 1)
if query == "is-owned":
    sys.exit(0 if args[0] in items else 1)
if query == "field":
    print(items[args[0]][args[1]])
if query == "deepest-first":
    def depth(item_id, seen=()):
        parent = items[item_id]["parent"]
        if parent not in items or parent in seen:
            return 0
        return 1 + depth(parent, seen + (item_id,))
    for item_id in sorted(items, key=depth, reverse=True):
        print(item_id)
PY
}

preflight() {
  command -v putio >/dev/null || die "putio CLI not on PATH"
  local status whoami account
  status="$(env -u PUTIO_CLI_TOKEN putio auth status --profile "${PROFILE}" --output json)" ||
    die "putio auth status failed; run putio auth login --profile ${PROFILE}"
  python3 -I -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("authenticated") and d.get("profile")==sys.argv[1] and d.get("source")=="profile" and d.get("apiBaseUrl")==sys.argv[2] else 1)' \
    "${PROFILE}" "${API_BASE_URL}" <<<"${status}" || die "putio profile ${PROFILE} is not signed in to ${API_BASE_URL}"
  whoami="$(cli whoami --fields info --output json)" || die "putio whoami failed"
  account="$(python3 -I -c 'import json,sys; i=json.load(sys.stdin)["info"]; print(i["user_id"] if i.get("username")==sys.argv[1] else "")' "${PROFILE}" <<<"${whoami}")"
  [[ "${account}" =~ ^[1-9][0-9]*$ ]] || die "putio profile ${PROFILE} does not resolve to the ${PROFILE} account"
  local bound
  bound="$(ledger account)"
  if [[ -z "${bound}" ]]; then
    record op=account id="${account}" state=bound
  elif [[ "${bound}" != "${account}" ]]; then
    die "the ledger belongs to account ${bound}, not ${account}"
  fi
}

require_settled() {
  ledger settled || die "the ledger has an open intent or an unknown outcome; reconcile read-only, adopt what exists, then settle"
}

require_owned_folder() { ledger is-folder "$1" || die "parent $1 is not a folder this run owns"; }

# Runs a write and records its outcome; prints the new id.
write_item() {
  local op="$1" name="$2" parent="$3" kind="$4"; shift 4
  record op="${op}" name="${name}" parent="${parent}" kind="${kind}" state=intent
  local out id
  if ! out="$(cli "$@" --output json 2>&1)"; then
    record op="${op}" name="${name}" parent="${parent}" kind="${kind}" state=unknown
    die "${op} ${name}: outcome unknown; reconcile read-only before any retry"
  fi
  id="$(python3 -I -c 'import json,sys; d=json.load(sys.stdin); d=d.get("file", d); print(d["id"])' <<<"${out}" 2>/dev/null)" || {
    record op="${op}" name="${name}" parent="${parent}" kind="${kind}" state=unknown
    die "${op} ${name}: no id in the response; reconcile read-only before any retry"
  }
  [[ "${kind}" == "FILE" ]] && kind="$(python3 -I -c 'import json,sys; d=json.load(sys.stdin); d=d.get("file", d); print(d.get("file_type","FILE"))' <<<"${out}")"
  record op="${op}" id="${id}" name="${name}" parent="${parent}" kind="${kind}" state=created
  echo "${id}"
}

# Exit 0 when files.get answers exactly 404, 1 when the id is still the
# ledger's item, 2 for anything else (another item, an unreadable answer).
probe() {
  local id="$1" out
  out="$(cli sdk call --operation files.get --args "[{\"id\":${id}}]" --execute --output json 2>&1 || true)"
  python3 -I -c '
import json, sys
# Every shape other than an exact 404 or an exact match is "unknown" (2).
try:
    d = json.loads(sys.stdin.read())
    error = d.get("error") if isinstance(d, dict) else None
    if isinstance(error, dict) and error.get("httpStatusCode") == 404:
        sys.exit(0)
    r = d.get("result") if isinstance(d, dict) else None
    if (isinstance(r, dict) and str(r.get("id")) == sys.argv[1] and r.get("name") == sys.argv[2]
            and str(r.get("parent_id")) == sys.argv[3]):
        sys.exit(1)
except Exception:
    pass
sys.exit(2)
' "${id}" "$(ledger field "${id}" name)" "$(ledger field "${id}" parent)" <<<"${out}"
}

case "${cmd}" in
  root)
    preflight; require_settled
    [[ -z "$(ledger owned)" ]] || die "run ${run} already owns a root; use another run name"
    name="android-verify-${run}"
    write_item mkdir "${name}" 0 FOLDER files mkdir --name "${name}" --parent-id 0
    ;;
  mkdir)
    preflight; require_settled; parent="${3:?parent id}"; name="${4:?name}"
    require_owned_folder "${parent}"
    write_item mkdir "${name}" "${parent}" FOLDER files mkdir --name "${name}" --parent-id "${parent}"
    ;;
  upload)
    preflight; require_settled; parent="${3:?parent id}"; path="${4:?local path}"; name="${5:-$(basename "${path}")}"
    [[ -f "${path}" ]] || die "no file ${path}"
    require_owned_folder "${parent}"
    write_item upload "${name}" "${parent}" FILE files upload --parent-id "${parent}" --path "${path}" --file-name "${name}"
    ;;
  intend)
    what="${3:?what the UI action will change}"
    require_settled
    record op=ui what="${what}" state=intent
    echo "intent recorded: ${what}"
    ;;
  adopt)
    preflight; id="${3:?item id}"
    [[ "${id}" =~ ^[1-9][0-9]*$ ]] || die "invalid id '${id}'"
    out="$(cli sdk call --operation files.get --args "[{\"id\":${id}}]" --execute --output json)" || die "files.get ${id} failed"
    fields="$(python3 -I -c 'import json,sys; r=json.load(sys.stdin)["result"]; print(json.dumps([str(r["parent_id"]), r["file_type"], r["name"]]))' <<<"${out}")" ||
      die "files.get ${id}: unexpected response"
    parent="$(python3 -I -c 'import json,sys; print(json.loads(sys.argv[1])[0])' "${fields}")"
    kind="$(python3 -I -c 'import json,sys; print(json.loads(sys.argv[1])[1])' "${fields}")"
    name="$(python3 -I -c 'import json,sys; print(json.loads(sys.argv[1])[2])' "${fields}")"
    # An owned item may have moved anywhere the app put it; a new one must sit
    # in an owned folder. The one exception is this run's own root container,
    # found after its mkdir answered with an unknown outcome.
    if ! ledger is-owned "${id}"; then
      if [[ "${parent}" == "0" ]]; then
        [[ "${kind}" == "FOLDER" && "${name}" == "android-verify-${run}" && -z "$(ledger owned)" ]] ||
          die "${id} sits at the account root and is not this run's root container"
      else
        require_owned_folder "${parent}"
      fi
    fi
    record op=adopt id="${id}" name="${name}" parent="${parent}" kind="${kind}" state=adopted
    echo "adopted ${id} ${kind} ${name} (parent ${parent})"
    ;;
  settle)
    what="${3:?what happened}"
    record op=ui what="${what}" state=settled
    echo "settled: ${what}"
    ;;
  owned)
    ledger owned
    ;;
  cleanup)
    preflight; require_settled
    order="$(ledger deepest-first)"
    # Check everything before deleting anything.
    pending=()
    for id in ${order}; do
      [[ "${id}" =~ ^[1-9][0-9]*$ ]] || die "refusing id '${id}'"
      name="$(ledger field "${id}" name)"
      set +e; probe "${id}"; state=$?; set -e
      case "${state}" in
        0) echo "gone ${id} ${name}"; continue ;;
        1) ;;
        *) die "${id} is no longer '${name}' in $(ledger field "${id}" parent); adopt it if the app changed it, else reconcile read-only. Nothing deleted" ;;
      esac
      if [[ "$(ledger field "${id}" kind)" == "FOLDER" ]]; then
        children="$(cli files list --parent-id "${id}" --per-page 100 --page-all --output json |
          python3 -I -c 'import json,sys; [print(f["id"]) for f in json.load(sys.stdin)["files"]]')" ||
          die "could not list folder ${id}; nothing deleted"
        for child in ${children}; do
          ledger is-owned "${child}" ||
            die "folder ${id} holds ${child}, which the ledger does not own; adopt it or reconcile. Nothing deleted"
        done
      fi
      pending+=("${id}")
    done
    for id in ${pending[@]+"${pending[@]}"}; do
      name="$(ledger field "${id}" name)"
      set +e; probe "${id}"; state=$?; set -e
      case "${state}" in
        0) echo "gone ${id} ${name} (removed with its folder)"; continue ;;
        1) ;;
        *) die "${id} changed during cleanup; stopped before deleting it" ;;
      esac
      # Its owned children are gone by now; anything left was added since the check.
      if [[ "$(ledger field "${id}" kind)" == "FOLDER" ]]; then
        left="$(cli files list --parent-id "${id}" --per-page 100 --output json |
          python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)["files"]))')" ||
          die "could not list folder ${id}; stopped before deleting it"
        [[ "${left}" == "0" ]] || die "folder ${id} still holds ${left} item(s); stopped before deleting it"
      fi
      record op=delete id="${id}" name="${name}" state=intent
      deleted=0
      if cli files delete --id "${id}" --skip-trash --output json >/dev/null 2>&1; then
        for _ in 1 2 3 4 5 6 7 8 9 10; do
          set +e; probe "${id}"; state=$?; set -e
          [[ "${state}" -eq 0 ]] && { deleted=1; break; }
          sleep 1
        done
      fi
      if ((deleted)); then
        record op=delete id="${id}" name="${name}" state=deleted
        echo "deleted ${id} ${name} (404 confirmed)"
      else
        record op=delete id="${id}" name="${name}" state=unknown
        die "delete ${id} unconfirmed; stopped. Reconcile read-only before anything else"
      fi
    done
    ;;
  *) usage ;;
esac
