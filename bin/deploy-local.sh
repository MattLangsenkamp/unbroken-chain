#!/bin/bash
# deploy-local.sh [cluster_name] [namespace]
#
# Idempotent local deployment script. Safe to run repeatedly — upgrades in place.
#
# Order of operations:
#   1. Pull and import grafana/otel-lgtm image into k3d
#   2. Install/upgrade all operators via helm upgrade --install
#   3. Create local-credentials Secret if it does not exist
#   4. helm upgrade --install the infra chart (Postgres, RabbitMQ, ESO, OTel)
#   5. helm upgrade --install each service chart directly
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
kubectl apply -f "https://github.com/rabbitmq/cluster-operator/releases/latest/download/cluster-operator.yml"
kubectl rollout status deployment/rabbitmq-cluster-operator -n rabbitmq-system --timeout=5m

echo "  OpenTelemetry Operator..."
helm upgrade --install opentelemetry-operator opentelemetry-operator \
  --repo https://open-telemetry.github.io/opentelemetry-helm-charts \
  --namespace opentelemetry-operator-system \
  --create-namespace \
  --set admissionWebhooks.certManager.enabled=false \
  --set admissionWebhooks.autoGenerateCert.enabled=true \
  --wait
# Restart the operator so it regenerates and patches the webhook cert, then wait
kubectl rollout restart deployment/opentelemetry-operator -n opentelemetry-operator-system
kubectl rollout status deployment/opentelemetry-operator -n opentelemetry-operator-system --timeout=2m

echo "  External Secrets Operator..."
helm upgrade --install external-secrets external-secrets \
  --repo https://charts.external-secrets.io \
  --namespace external-secrets \
  --create-namespace \
  --wait
kubectl wait --for=condition=Established crd/clustersecretstores.external-secrets.io --timeout=60s
kubectl wait --for=condition=Established crd/externalsecrets.external-secrets.io --timeout=60s

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
    --from-literal=postgres-username=app \
    --from-literal=postgres-password=changeme \
    --from-literal=rabbitmq-password=changeme
  echo "  ✅ local-credentials created"
  echo "  ⚠️  Update the secret with real values for non-trivial use:"
  echo "     kubectl edit secret local-credentials -n $NAMESPACE"
fi
echo ""

# ---------------------------------------------------------------------------
# 4. Deploy infra chart (Postgres, RabbitMQ, ESO, OTel)
# ---------------------------------------------------------------------------
echo "--- [4/5] Deploying infra chart ---"
helm upgrade --install unbroken-chain-infra "$UMBRELLA_DIR" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  --values "$UMBRELLA_DIR/values.yaml" \
  --wait \
  --timeout 5m
echo ""

# ---------------------------------------------------------------------------
# 5. Deploy service charts
# ---------------------------------------------------------------------------
echo "--- [5/5] Deploying services ---"

deploy_service() {
  local name="$1"
  local chart_dir="$2"
  echo "  $name..."
  helm upgrade --install "$name" "$chart_dir" \
    --namespace "$NAMESPACE" \
    --values "$chart_dir/values.yaml" \
    --wait \
    --timeout 2m
}

deploy_service github-gateway              "$REPO_ROOT/provider-gateways/github-gateway/k8s"
deploy_service github-gateway-presentation "$REPO_ROOT/provider-gateways/github-gateway/presentation/k8s"
deploy_service reader                      "$REPO_ROOT/reader/k8s"
deploy_service writer                      "$REPO_ROOT/writer/k8s"
deploy_service extraction-service          "$REPO_ROOT/extraction-service/k8s"
deploy_service ubc-control-plane           "$REPO_ROOT/ubc-control-plane/k8s"
deploy_service ubc-control-plane-presentation "$REPO_ROOT/ubc-control-plane/presentation/k8s"

echo ""
echo "✅ Deployment complete!"
echo "   Grafana:    http://localhost:3000"
echo "   Namespace:  $NAMESPACE"
echo "   Run 'make k9s' to inspect the cluster."
echo ""
