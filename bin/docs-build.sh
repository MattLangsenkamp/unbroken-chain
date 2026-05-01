#!/bin/bash
# docs-build.sh [out_dir]
#
# Build the static docs site with --strict so broken links fail the build.
#
# Args:
#     out_dir : output directory (default: site/_site)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/site/_site}"

cd "$ROOT_DIR/site"
uv run mkdocs build --strict --site-dir "$OUT_DIR"

echo ""
echo "✅ Built docs to $OUT_DIR"
