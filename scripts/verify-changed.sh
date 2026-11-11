#!/usr/bin/env bash
# Run the verify lanes that the changes since the merge base with BASE affect,
# including uncommitted and untracked files. BASE defaults to origin/main.
# scripts/ci_lanes.py owns the selection CI uses too; `./gradlew verify` runs
# every lane. Extra arguments go to Gradle.
#
#   scripts/verify-changed.sh [--base BASE] [gradle args...]

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

base=origin/main
if [[ "${1:-}" == "--base" ]]; then
  base="${2:?--base needs a ref}"
  shift 2
fi

merge_base="$(git merge-base "${base}" HEAD)"
tasks="$(
  {
    git diff --name-only --no-renames "${merge_base}"
    git ls-files --others --exclude-standard
  } | python3 -B scripts/ci_lanes.py --tasks
)"

if [[ -z "${tasks}" ]]; then
  echo "No verify lane covers the changes since ${base}."
  exit 0
fi

echo "Lanes: $(echo ${tasks})"
# shellcheck disable=SC2086
exec ./gradlew ${tasks} "$@"
