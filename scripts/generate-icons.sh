#!/usr/bin/env bash
# Regenerate locked Phosphor drawables, or verify them offline with --check.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${SCRIPT_DIR}/phosphor_icons.py" "$@"
