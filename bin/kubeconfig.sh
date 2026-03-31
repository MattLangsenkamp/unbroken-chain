#!/bin/bash
# kubeconfig.sh <env> [cluster_name]
#
# Sets the active kubeconfig context for a given environment,
# then prints the current context and cluster nodes to confirm the switch.
#
#   env=local  -> kubectl config use-context k3d-<cluster_name>
#                 cluster_name defaults to "unbroken-chain"
#
# Args:
#   $1  env          : local  (required; more envs will be added later)
#   $2  cluster_name : k3d cluster name for local env, defaults to "unbroken-chain"

set -e

ENV="${1:?Usage: kubeconfig.sh <env> [cluster_name]}"

case "$ENV" in
  local)
    CLUSTER_NAME="${2:-unbroken-chain}"
    echo "Setting kubeconfig context for local k3d cluster '${CLUSTER_NAME}'..."
    kubectl config use-context "k3d-${CLUSTER_NAME}"
    echo "✅ Context set to k3d-${CLUSTER_NAME}"
    ;;
  *)
    echo "❌ Unknown env: '${ENV}'. Supported: local"
    exit 1
    ;;
esac

echo ""
echo "Current context: $(kubectl config current-context)"
echo ""
kubectl get nodes
echo ""
