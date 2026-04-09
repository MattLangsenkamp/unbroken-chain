#!/bin/bash
# build-presentation.sh [image_name] [cluster_name]
#
# Compiles the Tyrian Scala.js app, bundles it with Vite, and builds an
# nginx Docker image. Optionally imports the image into a k3d cluster.
#
# Steps:
#   1. ./mill presentation.fullLinkJS   — optimised Scala.js linker output
#   2. npm install                       — ensure deps are present
#   3. SCALA_JS_DEST=fullLinkJS npm run build — Vite bundles to presentation/dist/
#   4. docker build                      — nginx image wrapping dist/
#   5. k3d image import (if cluster_name provided)
#
# Args:
#   $1  image_name   : Docker image name with tag, defaults to "unbrokenchain/presentation:latest"
#   $2  cluster_name : k3d cluster to import image into; skipped if not provided

set -e

IMAGE_NAME="${1:-unbrokenchain/presentation:latest}"
CLUSTER_NAME="${2:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Building presentation image: $IMAGE_NAME ==="
echo ""

# ---------------------------------------------------------------------------
# 1. Compile Scala.js
# ---------------------------------------------------------------------------
echo "--- [1/4] Compiling Scala.js (fullLinkJS) ---"
cd "$REPO_ROOT"
./mill presentation.fullLinkJS
echo ""

# ---------------------------------------------------------------------------
# 2 & 3. npm install + Vite bundle
# ---------------------------------------------------------------------------
echo "--- [2/4] Installing npm dependencies ---"
cd "$REPO_ROOT/presentation"
npm install
echo ""

echo "--- [3/4] Bundling with Vite ---"
SCALA_JS_DEST="../out/presentation/fullLinkJS.dest/main.js" npm run build
echo ""

# ---------------------------------------------------------------------------
# 4. Docker build (context = repo root so COPY presentation/... resolves)
# ---------------------------------------------------------------------------
echo "--- [4/4] Building Docker image ---"
cd "$REPO_ROOT"
docker build -t "$IMAGE_NAME" -f presentation/Dockerfile .
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
