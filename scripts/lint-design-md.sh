#!/usr/bin/env bash
# Lints DESIGN.md against the design.md format; warnings fail like errors.
set -euo pipefail

version="${1:?usage: lint-design-md.sh <@google/design.md version>}"
status=0
report="$(npx --yes "@google/design.md@${version}" lint DESIGN.md)" || status=$?
printf '%s\n' "$report"
[ "$status" -eq 0 ] || exit "$status"
printf '%s' "$report" | python3 -c '
import json, sys
summary = json.load(sys.stdin)["summary"]
sys.exit(1 if summary["errors"] or summary["warnings"] else 0)
'
