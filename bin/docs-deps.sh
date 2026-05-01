#!/bin/bash
# docs-deps.sh
#
# Sync Python dependencies for the docs site via uv.
# Creates site/.venv if missing and installs the locked deps.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR/site"
uv sync

echo ""
echo "✅ Docs deps synced into site/.venv"
