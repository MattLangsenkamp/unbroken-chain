#!/bin/bash
# psql.sh <db_name> [namespace] [cluster_name]
#
# Opens an interactive psql session to the given database on the platform Postgres cluster.
# Reads credentials from the postgres-credentials Secret in the namespace.
#
# Args:
#   $1  db_name      : database name to connect to (required)
#   $2  namespace    : Kubernetes namespace, defaults to "unbroken-chain"
#   $3  cluster_name : CNPG cluster name, defaults to "unbroken-chain-pg"

set -e

DB="${1}"
NAMESPACE="${2:-unbroken-chain}"
CLUSTER="${3:-unbroken-chain-pg}"

if [ -z "$DB" ]; then
  echo "Usage: $0 <db_name> [namespace] [cluster_name]"
  exit 1
fi

POD="${CLUSTER}-1"

USERNAME=$(kubectl get secret postgres-credentials -n "$NAMESPACE" -o jsonpath='{.data.username}' | base64 -d)
PASSWORD=$(kubectl get secret postgres-credentials -n "$NAMESPACE" -o jsonpath='{.data.password}' | base64 -d)

echo "Connecting to '$DB' on $POD ($NAMESPACE)..."
kubectl exec -it "$POD" -n "$NAMESPACE" -- \
  env PGPASSWORD="$PASSWORD" psql -h localhost -U "$USERNAME" -d "$DB"
