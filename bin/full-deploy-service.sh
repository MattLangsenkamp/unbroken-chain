#!/bin/bash
# full-deploy-service.sh <mill_module> <image> <release> <chart_dir> [cluster] [namespace]
#
# Full dev-iteration cycle for a single service:
#   1. Build the Docker image via Mill
#   2. Import it into k3d
#   3. helm upgrade --install the service chart
#   4. Force a rollout restart so the new image is picked up
#   5. Wait for the rollout to complete
#
# Args:
#   $1  mill_module : Mill target for docker build, e.g. provider-gateways.github-gateway.server
#   $2  image       : Docker image name with tag, e.g. unbrokenchain/github-gateway:latest
#   $3  release     : Helm release / k8s Deployment name, e.g. github-gateway
#   $4  chart_dir   : Path to the Helm chart directory, e.g. provider-gateways/github-gateway/k8s
#   $5  cluster     : k3d cluster name (default: unbroken-chain)
#   $6  namespace   : Kubernetes namespace (default: unbroken-chain)

set -e

MILL_MODULE="${1}"
IMAGE="${2}"
RELEASE="${3}"
CHART_DIR="${4}"
CLUSTER="${5:-unbroken-chain}"
NAMESPACE="${6:-unbroken-chain}"

if [ -z "$MILL_MODULE" ] || [ -z "$IMAGE" ] || [ -z "$RELEASE" ] || [ -z "$CHART_DIR" ]; then
  echo "Usage: $0 <mill_module> <image> <release> <chart_dir> [cluster] [namespace]"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== full-deploy: $RELEASE ==="

echo "  [1/4] Building $IMAGE..."
"$REPO_ROOT/mill" "${MILL_MODULE}.docker.build"

echo "  [2/4] Importing into k3d cluster '$CLUSTER'..."
k3d image import "$IMAGE" --cluster "$CLUSTER"

echo "  [3/4] Deploying chart '$CHART_DIR'..."
helm upgrade --install "$RELEASE" "$REPO_ROOT/$CHART_DIR" \
  --namespace "$NAMESPACE" \
  --values "$REPO_ROOT/$CHART_DIR/values.yaml" \
  --wait

echo "  [4/4] Restarting and waiting for rollout..."
kubectl rollout restart deployment/"$RELEASE" -n "$NAMESPACE"
kubectl rollout status deployment/"$RELEASE" -n "$NAMESPACE" --timeout=2m

echo "✅ $RELEASE deployed"
