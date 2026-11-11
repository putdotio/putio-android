#!/usr/bin/env bash
# Fetch the licensed brand fonts design/brand-fonts.lock.json pins. Idempotent.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" syncBrandFonts "$@"
