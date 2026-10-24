#!/usr/bin/env bash
# Fetch the licensed brand fonts design/brand-fonts.lock.json pins. Idempotent.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${SCRIPT_DIR}/brand_fonts.py" "$@"
