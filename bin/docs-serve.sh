#!/bin/bash
# docs-serve.sh [addr]
#
# Serve the docs site locally with live reload.
#
# Args:
#     addr : bind address (default: 0.0.0.0:8000)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ADDR="${1:-0.0.0.0:8000}"

cd "$ROOT_DIR/site"
uv run mkdocs serve -a "$ADDR"
