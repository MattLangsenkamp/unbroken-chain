#!/bin/bash
# k9s.sh [cluster_name]
#
# Launches k9s pointed at the local k3d cluster context.
# Sets the kubeconfig context first to ensure k9s opens the right cluster.
#
# Args:
#   $1  cluster_name : k3d cluster name, defaults to "unbroken-chain"

set -e

CLUSTER_NAME="${1:-unbroken-chain}"
CONTEXT="k3d-${CLUSTER_NAME}"

command -v k9s >/dev/null 2>&1 || { echo "❌ k9s is not installed. Install: https://k9scli.io/topics/install/"; exit 1; }

echo "Launching k9s for context '${CONTEXT}'..."
exec k9s --context "${CONTEXT}"
