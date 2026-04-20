#!/bin/bash
# build-migrations.sh <service_dir> <image_name> [cluster_name]
#
# Builds the Flyway migration Docker image for a service and imports it into k3d.
#
# Args:
#   $1  service_dir  : path to the service root relative to repo root (e.g. provider-gateways/github-gateway)
#   $2  image_name   : Docker image name with tag (e.g. unbrokenchain/github-gateway-migrations:latest)
#   $3  cluster_name : k3d cluster name, defaults to "unbroken-chain"

set -e

SERVICE_DIR="${1}"
IMAGE_NAME="${2}"
CLUSTER_NAME="${3:-unbroken-chain}"

if [ -z "$SERVICE_DIR" ] || [ -z "$IMAGE_NAME" ]; then
  echo "Usage: $0 <service_dir> <image_name> [cluster_name]"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVICE_ROOT="$REPO_ROOT/$SERVICE_DIR"
DOCKERFILE="$SERVICE_ROOT/db-migrations/Dockerfile"

if [ ! -f "$DOCKERFILE" ]; then
  echo "❌ No db-migrations/Dockerfile found at $DOCKERFILE"
  exit 1
fi

echo "Building migration image '$IMAGE_NAME' from $SERVICE_DIR/db-migrations/..."
docker build -t "$IMAGE_NAME" -f "$DOCKERFILE" "$SERVICE_ROOT"

echo "Importing '$IMAGE_NAME' into k3d cluster '$CLUSTER_NAME'..."
k3d image import "$IMAGE_NAME" --cluster "$CLUSTER_NAME"

echo "✅ Migration image ready: $IMAGE_NAME"
