#!/bin/bash
# load-github-app-secrets.sh [namespace]
#
# Loads GitHub App credentials from local files into the local-credentials
# Secret. The External Secrets Operator then syncs them into
# github-gateway-credentials, which the github-gateway pod mounts as env vars.
#
# Default file paths (override via env vars):
#   .local/github-app-private-key.pem    GITHUB_APP_PEM_PATH
#   .local/github-webhook-secret         GITHUB_WEBHOOK_SECRET_PATH
#   .local/verifier-encryption-key       VERIFIER_ENCRYPTION_KEY_PATH
#
# .local/ is gitignored — files placed there never reach version control.
# Each file is optional; missing files are skipped with a warning. The
# github-gateway pod will only start once all three keys are present in
# the synced Secret.
#
# After patching, forces an ExternalSecret sync, waits for the target Secret
# to be updated, and restarts the github-gateway deployment.

set -e

NAMESPACE="${1:-unbroken-chain}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCAL_DIR="$REPO_ROOT/.local"

PEM_PATH="${GITHUB_APP_PEM_PATH:-$LOCAL_DIR/github-app-private-key.pem}"
WEBHOOK_PATH="${GITHUB_WEBHOOK_SECRET_PATH:-$LOCAL_DIR/github-webhook-secret}"
VERIFIER_PATH="${VERIFIER_ENCRYPTION_KEY_PATH:-$LOCAL_DIR/verifier-encryption-key}"

echo "=== Loading github-gateway secrets into local-credentials ==="
echo "Namespace: $NAMESPACE"
echo ""

if ! kubectl get secret local-credentials -n "$NAMESPACE" >/dev/null 2>&1; then
  echo "❌ Secret 'local-credentials' not found in namespace '$NAMESPACE'."
  echo "   Run 'make deploy-app' first to bootstrap the cluster."
  exit 1
fi

patched_any=0

patch_secret_from_file() {
  local key="$1"
  local path="$2"

  if [[ ! -f "$path" ]]; then
    echo "  ⚠️  ${key}: $path not found — skipping"
    return
  fi

  local patch_file
  patch_file="$(mktemp)"

  # |- block scalar: preserves internal newlines (needed for multi-line PEM),
  # strips the trailing newline so single-line secrets aren't silently padded.
  {
    echo "stringData:"
    echo "  ${key}: |-"
    sed 's/^/    /' "$path"
  } > "$patch_file"

  kubectl patch secret local-credentials \
    -n "$NAMESPACE" \
    --type=merge \
    --patch-file "$patch_file" >/dev/null

  rm -f "$patch_file"
  echo "  ✅ ${key}: loaded from $path"
  patched_any=1
}

patch_secret_from_file "github-app-private-key-pem" "$PEM_PATH"
patch_secret_from_file "github-webhook-secret"      "$WEBHOOK_PATH"
patch_secret_from_file "verifier-encryption-key"    "$VERIFIER_PATH"

if [[ $patched_any -eq 0 ]]; then
  echo ""
  echo "❌ No files found. Place credentials under $LOCAL_DIR/ or set the"
  echo "   GITHUB_APP_PEM_PATH / GITHUB_WEBHOOK_SECRET_PATH /"
  echo "   VERIFIER_ENCRYPTION_KEY_PATH env vars."
  exit 1
fi

echo ""

if kubectl get externalsecret github-gateway-credentials -n "$NAMESPACE" >/dev/null 2>&1; then
  echo "Forcing ExternalSecret refresh..."
  kubectl annotate externalsecret github-gateway-credentials \
    -n "$NAMESPACE" \
    "force-sync=$(date +%s)" \
    --overwrite >/dev/null

  echo "Waiting for github-gateway-credentials to sync..."
  for i in $(seq 1 30); do
    if kubectl get secret github-gateway-credentials -n "$NAMESPACE" >/dev/null 2>&1 \
         && kubectl get secret github-gateway-credentials -n "$NAMESPACE" \
              -o jsonpath='{.data.github-app-private-key-pem}' 2>/dev/null | grep -q .; then
      echo "  ✅ synced"
      break
    fi
    sleep 1
  done
else
  echo "ℹ️  ExternalSecret 'github-gateway-credentials' not deployed yet."
  echo "   Run 'make deploy-app' to apply the umbrella chart — ESO will sync"
  echo "   the values you just loaded into local-credentials on first reconcile."
fi

if kubectl get deployment github-gateway -n "$NAMESPACE" >/dev/null 2>&1; then
  echo "Restarting github-gateway deployment..."
  kubectl rollout restart deployment/github-gateway -n "$NAMESPACE" >/dev/null
  echo "  ✅ restarted"
fi

echo ""
echo "Tail logs with:"
echo "  kubectl logs -n $NAMESPACE -l app=github-gateway -f"
