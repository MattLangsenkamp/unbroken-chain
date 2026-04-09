#!/bin/bash
# build-presentation.sh <presentation_dir> <image_name> [cluster_name]
#
# Compiles a Tyrian Scala.js presentation app, bundles it with Vite,
# and builds an nginx Docker image. Optionally imports the image into k3d.
#
# The Mill module path is derived from presentation_dir by replacing / with .
# (e.g. ubc-control-plane/presentation → ubc-control-plane.presentation).
# The fullLinkJS output path is likewise derived from the dir.
#
# Args:
#   $1  presentation_dir : path relative to repo root (e.g. ubc-control-plane/presentation)
#   $2  image_name       : Docker image name with tag (e.g. unbrokenchain/ubc-control-plane-presentation:latest)
#   $3  cluster_name     : k3d cluster to import image into; skipped if not provided

set -e

PRESENTATION_DIR="${1:?Usage: $0 <presentation_dir> <image_name> [cluster_name]}"
IMAGE_NAME="${2:?Usage: $0 <presentation_dir> <image_name> [cluster_name]}"
CLUSTER_NAME="${3:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FULL_PRESENTATION_DIR="$REPO_ROOT/$PRESENTATION_DIR"

# Derive Mill module target by replacing / with .
# e.g. ubc-control-plane/presentation → ubc-control-plane.presentation
MILL_MODULE="${PRESENTATION_DIR//\//.}"

# Mill places fullLinkJS output at out/<module-path>/fullLinkJS.dest/
SCALA_JS_OUT="$REPO_ROOT/out/$PRESENTATION_DIR/fullLinkJS.dest/main.js"

echo "=== Building presentation image: $IMAGE_NAME ==="
echo "    Module: $MILL_MODULE"
echo ""

# ---------------------------------------------------------------------------
# 1. Compile Scala.js
# ---------------------------------------------------------------------------
echo "--- [1/4] Compiling Scala.js (fullLinkJS) ---"
cd "$REPO_ROOT"
./mill "${MILL_MODULE}.fullLinkJS"
echo ""

# ---------------------------------------------------------------------------
# 2. npm install
# ---------------------------------------------------------------------------
echo "--- [2/4] Installing npm dependencies ---"
cd "$FULL_PRESENTATION_DIR"
npm install
echo ""

# ---------------------------------------------------------------------------
# 3. Vite bundle (SCALA_JS_DEST → absolute path → vite.config.js derives relative path)
# ---------------------------------------------------------------------------
echo "--- [3/4] Bundling with Vite ---"
SCALA_JS_DEST="$SCALA_JS_OUT" npm run build
echo ""

# ---------------------------------------------------------------------------
# 4. Docker build (context = presentation dir — Dockerfile uses COPY dist/ directly)
# ---------------------------------------------------------------------------
echo "--- [4/4] Building Docker image ---"
docker build -t "$IMAGE_NAME" "$FULL_PRESENTATION_DIR"
echo ""

# ---------------------------------------------------------------------------
# Optional: import into k3d
# ---------------------------------------------------------------------------
if [ -n "$CLUSTER_NAME" ]; then
  echo "Importing '$IMAGE_NAME' into k3d cluster '$CLUSTER_NAME'..."
  k3d image import "$IMAGE_NAME" --cluster "$CLUSTER_NAME"
  echo ""
fi

echo "✅ Presentation image ready: $IMAGE_NAME"
