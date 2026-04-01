#!/bin/bash
# start-local-env.sh [cluster_name]
#
# Creates and starts the local k3d cluster for development.
# Runs check-deps.sh first, then:
#   1. Creates the k3d cluster if it doesn't exist (mounts repo root at /repo)
#   2. Sets the kubeconfig context to the new cluster
#
# Args:
#   $1  cluster_name : k3d cluster name, defaults to "unbroken-chain"

set -e

CLUSTER_NAME="${1:-unbroken-chain}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

"$SCRIPT_DIR/check-deps.sh" "$CLUSTER_NAME"

echo "Starting local environment..."

if k3d cluster list 2>/dev/null | grep -q "${CLUSTER_NAME}"; then
  echo "✅ k3d cluster '${CLUSTER_NAME}' already exists"
else
  echo "Creating k3d cluster '${CLUSTER_NAME}'..."
  k3d cluster create "${CLUSTER_NAME}" \
    --api-port 6550 \
    --port "8080:80@loadbalancer" \
    --port "8443:443@loadbalancer" \
    --volume "${REPO_ROOT}:/repo@all"
  echo "✅ k3d cluster '${CLUSTER_NAME}' created"
fi

echo ""
echo "Setting kubeconfig context..."
kubectl config use-context "k3d-${CLUSTER_NAME}"
echo ""

echo "✅ Local environment ready!"
echo "   Kubernetes: k3d-${CLUSTER_NAME}"
echo "   Context:    $(kubectl config current-context)"
echo ""
kubectl get nodes
echo ""
