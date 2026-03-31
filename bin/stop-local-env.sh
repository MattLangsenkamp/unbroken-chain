#!/bin/bash
# stop-local-env.sh [cluster_name]
#
# Stops and deletes the local k3d cluster.
# This is non-destructive to source code — only the cluster is removed.
#
# Args:
#   $1  cluster_name : k3d cluster name, defaults to "unbroken-chain"

set -e

CLUSTER_NAME="${1:-unbroken-chain}"

echo "Stopping local environment..."

if k3d cluster list 2>/dev/null | grep -q "${CLUSTER_NAME}"; then
  echo "Deleting k3d cluster '${CLUSTER_NAME}'..."
  k3d cluster delete "${CLUSTER_NAME}"
  echo "✅ k3d cluster '${CLUSTER_NAME}' deleted"
else
  echo "✅ k3d cluster '${CLUSTER_NAME}' does not exist — nothing to do"
fi

echo ""
echo "✅ Local environment stopped"
echo ""
