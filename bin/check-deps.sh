#!/bin/bash
# check-deps.sh [cluster_name]
#
# Verifies all local development dependencies are installed and running.
# Checks: docker, k3d, kubectl, k9s, mill
# Also verifies the Docker daemon is up and the k3d cluster exists.
# Exits non-zero if any required binary is missing.

set -e

CLUSTER_NAME="${1:-unbroken-chain}"

echo "Checking local development dependencies..."

command -v docker   >/dev/null 2>&1 || { echo "❌ docker is not installed.   Install: https://docs.docker.com/engine/install/"; exit 1; }
command -v k3d      >/dev/null 2>&1 || { echo "❌ k3d is not installed.      Install: curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash"; exit 1; }
command -v kubectl  >/dev/null 2>&1 || { echo "❌ kubectl is not installed.  Install: https://kubernetes.io/docs/tasks/tools/"; exit 1; }
command -v k9s      >/dev/null 2>&1 || { echo "❌ k9s is not installed.      Install: https://k9scli.io/topics/install/"; exit 1; }
command -v ./mill   >/dev/null 2>&1 || { echo "❌ mill is not found.         Install: https://mill-build.org/mill/Installation_IDE_Support.html"; exit 1; }

echo "✅ All dependencies installed"
echo ""

echo "Checking Docker daemon..."
docker info >/dev/null 2>&1 || { echo "❌ Docker daemon is not running. Start Docker and retry."; exit 1; }
echo "✅ Docker daemon is running"
echo ""

echo "Checking k3d cluster..."
if k3d cluster list 2>/dev/null | grep -q "${CLUSTER_NAME}"; then
  echo "✅ k3d cluster '${CLUSTER_NAME}' exists"
else
  echo "⚠️  k3d cluster '${CLUSTER_NAME}' does not exist. Run 'make start' to create it."
fi
echo ""
