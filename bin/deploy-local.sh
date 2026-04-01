#!/bin/bash
# deploy-local.sh [cluster_name] [namespace]
#
# Idempotent local deployment script. Safe to run repeatedly — upgrades in place.
#
# Order of operations:
#   1. Pull and import grafana/otel-lgtm image into k3d
#   2. Install/upgrade all operators via helm upgrade --install
#   3. Create local-credentials Secret if it does not exist
#   4. helm dependency update on the umbrella chart
#   5. helm upgrade --install the umbrella chart
#
# Args:
#   $1  cluster_name : k3d cluster name, defaults to "unbroken-chain"
#   $2  namespace    : Kubernetes namespace, defaults to "unbroken-chain"

set -e

CLUSTER_NAME="${1:-unbroken-chain}"
NAMESPACE="${2:-unbroken-chain}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
UMBRELLA_DIR="$REPO_ROOT/umbrella"

echo "=== Unbroken Chain — Local Deploy ==="
echo "Cluster:   $CLUSTER_NAME"
echo "Namespace: $NAMESPACE"
echo ""

# ---------------------------------------------------------------------------
# 1. Pull and import grafana/otel-lgtm
# ---------------------------------------------------------------------------
echo "--- [1/5] Importing grafana/otel-lgtm into k3d ---"
docker pull grafana/otel-lgtm:latest
k3d image import grafana/otel-lgtm:latest --cluster "$CLUSTER_NAME"
echo ""

# ---------------------------------------------------------------------------
# 2. Install/upgrade operators
# ---------------------------------------------------------------------------
echo "--- [2/5] Installing operators ---"

echo "  CloudNativePG..."
helm upgrade --install cloudnative-pg cloudnative-pg \
  --repo https://cloudnative-pg.github.io/charts \
  --namespace cnpg-system \
  --create-namespace \
  --wait

echo "  RabbitMQ Cluster Operator..."
helm upgrade --install rabbitmq-cluster-operator \
  oci://registry-1.docker.io/bitnamicharts/rabbitmq-cluster-operator \
  --namespace rabbitmq-system \
  --create-namespace \
  --wait

echo "  OpenTelemetry Operator..."
helm upgrade --install opentelemetry-operator opentelemetry-operator \
  --repo https://open-telemetry.github.io/opentelemetry-helm-charts \
  --namespace opentelemetry-operator-system \
  --create-namespace \
  --set admissionWebhooks.certManager.enabled=false \
  --set admissionWebhooks.autoGenerateCert.enabled=true \
  --wait

echo "  External Secrets Operator..."
helm upgrade --install external-secrets external-secrets \
  --repo https://charts.external-secrets.io \
  --namespace external-secrets \
  --create-namespace \
  --wait

echo ""

# ---------------------------------------------------------------------------
# 3. Create namespace and local-credentials Secret
# ---------------------------------------------------------------------------
echo "--- [3/5] Creating namespace and local-credentials Secret ---"

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

if kubectl get secret local-credentials -n "$NAMESPACE" >/dev/null 2>&1; then
  echo "  ✅ local-credentials already exists — skipping"
else
  echo "  Creating local-credentials with placeholder values..."
  kubectl create secret generic local-credentials \
    --namespace "$NAMESPACE" \
    --from-literal=postgres-password=changeme \
    --from-literal=rabbitmq-password=changeme
  echo "  ✅ local-credentials created"
  echo "  ⚠️  Update the secret with real values for non-trivial use:"
  echo "     kubectl edit secret local-credentials -n $NAMESPACE"
fi
echo ""

# ---------------------------------------------------------------------------
# 4. Helm dependency update
# ---------------------------------------------------------------------------
echo "--- [4/5] Running helm dependency update ---"
helm dependency update "$UMBRELLA_DIR"
echo ""

# ---------------------------------------------------------------------------
# 5. Helm upgrade --install
# ---------------------------------------------------------------------------
echo "--- [5/5] Deploying umbrella chart ---"
helm upgrade --install unbroken-chain "$UMBRELLA_DIR" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  --values "$UMBRELLA_DIR/values.yaml" \
  --wait \
  --timeout 5m

echo ""
echo "✅ Deployment complete!"
echo "   Grafana:    http://localhost:3000"
echo "   Namespace:  $NAMESPACE"
echo "   Run 'make k9s' to inspect the cluster."
echo ""
