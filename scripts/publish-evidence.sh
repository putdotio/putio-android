#!/usr/bin/env bash
# Publish one validated evidence file through the installed Attach CLI.
#
#   scripts/publish-evidence.sh <file> --pr N [--repo OWNER/NAME] [--markdown] [--dry-run]
#
# Default output is the hosted preview URL. --markdown requests an inline raw
# object embed. This wrapper never authenticates and never touches gh auth.

# A caller may enable xtrace on this script. Disable it before any CLI output
# can enter a shell assignment and therefore a durable trace.
set +x
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
    --repo)
      [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || {
        log_error "--repo requires a value"
        exit 64
      }
      repo="$2"
      shift
      ;;
    --pr)
      [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || {
        log_error "--pr requires a value"
        exit 64
      }
      pr="$2"
      shift
      ;;
    --markdown) markdown=1 ;;
    --dry-run) dry_run=1 ;;
    *) log_error "unknown argument: $1"; exit 64 ;;
  esac
  shift
done

[[ -f "${file}" && -s "${file}" ]] || fail "evidence file is missing or empty: ${file}"
case "${file##*/}" in
  *.corrupt|*.black.*|*.idle|*.unverified.*)
    fail "refusing to publish quarantined evidence: ${file}"
    ;;
esac
[[ "${repo}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail "--repo must be OWNER/NAME"
[[ "${pr}" =~ ^[1-9][0-9]*$ ]] || fail "--pr must be a positive integer"
evidence_name="${file##*/}"
markdown_escape=$'\\'
if [[ "${markdown}" == "1" ]] &&
   [[ "${evidence_name}" == *'['* || "${evidence_name}" == *']'* ||
      "${evidence_name}" == *"${markdown_escape}"* || "${evidence_name}" == *$'\n'* ||
      "${evidence_name}" == *$'\r'* ]]; then
  fail "evidence filename cannot be represented safely in Markdown: ${file}"
fi

attach_origin="${ATTACH_API_BASE:-https://attach.uinaf.dev}"
while [[ "${attach_origin}" == */ ]]; do attach_origin="${attach_origin%/}"; done
[[ -n "${attach_origin}" ]] || fail "ATTACH_API_BASE must not be empty"
if [[ "${attach_origin}" != "https://attach.uinaf.dev" && -z "${ATTACH_GITHUB_CLIENT_ID:-}" ]]; then
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
output="$("${attach_command[@]}" "${args[@]}" 2>/dev/null)"
status=$?
set -e
if [[ "${status}" -ne 0 ]]; then
  # Do not relay arbitrary CLI output: authentication failures may include a
  # short-lived device code, and credentials must never reach durable logs.
  if [[ "${status}" -eq 2 ]]; then
    fail "Attach CLI rejected the file or metadata (exit 2); validate with --dry-run"
  fi
  fail "Attach upload failed (exit ${status}); run attach login interactively if needed, then verify GitHub user allowlisting and service availability"
fi

[[ -n "${output}" ]] || fail "Attach CLI returned empty output"
[[ "${output}" != *$'\n'* && "${output}" != *$'\r'* ]] || fail "Attach CLI returned multiline output"
preview_prefix="${attach_origin}/p/"
raw_link_prefix="](${attach_origin}/o/"
if [[ "${dry_run}" != "1" && "${markdown}" != "1" ]]; then
  preview_key="${output#"${preview_prefix}"}"
  if [[ "${output}" == "${preview_key}" || ! "${preview_key}" =~ ^[A-Za-z0-9_-]{20,}$ ]]; then
    fail "Attach CLI did not return one preview URL for ${attach_origin}"
  fi
fi
if [[ "${dry_run}" != "1" && "${markdown}" == "1" ]]; then
  markdown_lead="${output%%"${raw_link_prefix}"*}"
  markdown_tail="${output#*"${raw_link_prefix}"}"
  markdown_key="${markdown_tail%)}"
  expected_markdown="${markdown_lead}${raw_link_prefix}${markdown_key})"
  if [[ "${markdown_lead}" == '!'\[* ]]; then
    markdown_label="${markdown_lead:2}"
  elif [[ "${markdown_lead}" == \[* ]]; then
    markdown_label="${markdown_lead:1}"
  else
    markdown_label="]"
  fi
  if [[ "${markdown_label}" != "${evidence_name}" ||
        "${markdown_tail}" == "${output}" || ! "${markdown_key}" =~ ^[A-Za-z0-9_-]{20,}$ ||
        "${output}" != "${expected_markdown}" ]]; then
    fail "Attach CLI did not return one Markdown raw-object link for ${attach_origin}"
  fi
fi
printf '%s\n' "${output}"
