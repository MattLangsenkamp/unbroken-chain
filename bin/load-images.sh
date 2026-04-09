#!/bin/bash
# load-images.sh [cluster_name]
#
# Imports all service Docker images into the local k3d cluster.
# Images must be built locally first via:
#   ./mill <service>.server.docker.build
#
# Args:
#   $1  cluster_name : k3d cluster name, defaults to "unbroken-chain"

set -e

CLUSTER_NAME="${1:-unbroken-chain}"

IMAGES=(
  "unbrokenchain/github-gateway:latest"
  "unbrokenchain/reader:latest"
  "unbrokenchain/writer:latest"
  "unbrokenchain/extraction-service:latest"
  "unbrokenchain/ubc-control-plane:latest"
  "unbrokenchain/presentation:latest"
)

echo "Loading images into k3d cluster '${CLUSTER_NAME}'..."

for image in "${IMAGES[@]}"; do
  echo "  Importing ${image}..."
  k3d image import "${image}" -c "${CLUSTER_NAME}"
done

echo ""
echo "✅ All images loaded into k3d cluster '${CLUSTER_NAME}'"
