#!/usr/bin/env bash
# Publish one validated evidence file through the installed Attach CLI.
#
#   scripts/publish-evidence.sh <file> --pr N [--repo OWNER/NAME] [--markdown] [--dry-run]
#
# Default output is the hosted preview URL. --markdown requests an inline raw
# object embed. This wrapper never authenticates and never touches gh auth.

set -euo pipefail

log_error() { printf 'publish-evidence: %s\n' "$*" >&2; }
fail() { log_error "$*"; exit 1; }

file="${1:-}"
[[ -n "${file}" && "${file}" != --* ]] || {
  log_error "evidence file is required"
  exit 64
}
shift

repo="putdotio/putio-android"
pr=""
markdown=0
dry_run=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) repo="${2:?--repo requires a value}"; shift ;;
    --pr) pr="${2:?--pr requires a value}"; shift ;;
    --markdown) markdown=1 ;;
    --dry-run) dry_run=1 ;;
    *) log_error "unknown argument: $1"; exit 64 ;;
  esac
  shift
done

[[ -f "${file}" && -s "${file}" ]] || fail "evidence file is missing or empty: ${file}"
[[ "${repo}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail "--repo must be OWNER/NAME"
[[ "${pr}" =~ ^[1-9][0-9]*$ ]] || fail "--pr must be a positive integer"

if [[ -n "${ATTACH_API_BASE:-}" && "${ATTACH_API_BASE}" != "https://attach.uinaf.dev" && -z "${ATTACH_GITHUB_CLIENT_ID:-}" ]]; then
  fail "custom ATTACH_API_BASE requires ATTACH_GITHUB_CLIENT_ID"
fi

attach_command=()
if command -v attach >/dev/null 2>&1; then
  attach_command=(attach)
elif command -v gh >/dev/null 2>&1 && gh attach help >/dev/null 2>&1; then
  attach_command=(gh attach)
else
  fail "Attach CLI not found; install attach or the gh attach extension"
fi

args=(put "${file}" --repo "${repo}" --pr "${pr}")
[[ "${markdown}" == "1" ]] && args+=(--markdown)
[[ "${dry_run}" == "1" ]] && args+=(--dry-run)

set +e
output="$("${attach_command[@]}" "${args[@]}" 2>&1)"
status=$?
set -e
if [[ "${status}" -ne 0 ]]; then
  # Do not relay arbitrary CLI output: authentication failures may include a
  # short-lived device code, and credentials must never reach durable logs.
  fail "Attach CLI failed (exit ${status}); run attach login interactively, then verify repository allowlisting and retry"
fi

[[ -n "${output}" ]] || fail "Attach CLI returned empty output"
printf '%s\n' "${output}"
