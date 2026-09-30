#!/usr/bin/env bash
# Sync locked @putdotio/design assets, or verify them offline with --check.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${SCRIPT_DIR}/design_assets.py" "$@"
