#!/bin/bash
# rabbitmq-admin.sh <command> [cluster_name]
#
# Inspect and interact with the RabbitMQ cluster running in k3d.
#
# Commands:
#   ui          Port-forward the management UI to localhost:15672 (browser)
#   queues      List all queues with message and consumer counts
#   exchanges   List all exchanges with type and durability
#   bindings    List all bindings (source → destination)
#
# Args:
#   $1  command      : ui | queues | exchanges | bindings  (default: ui)
#   $2  cluster_name : k3d cluster name                    (default: unbroken-chain)

set -e

COMMAND="${1:-ui}"
CLUSTER_NAME="${2:-unbroken-chain}"
NAMESPACE="$CLUSTER_NAME"
RABBITMQ_CLUSTER="${CLUSTER_NAME}-rabbitmq"
POD="${RABBITMQ_CLUSTER}-server-0"

case "$COMMAND" in
  ui)
    echo "RabbitMQ Management UI → http://localhost:15672"
    echo ""
    echo "Credentials:"
    echo "  username: $(kubectl get secret "${RABBITMQ_CLUSTER}-default-user" -n "$NAMESPACE" -o jsonpath='{.data.username}' | base64 -d)"
    echo "  password: $(kubectl get secret "${RABBITMQ_CLUSTER}-default-user" -n "$NAMESPACE" -o jsonpath='{.data.password}' | base64 -d)"
    echo ""
    echo "Press Ctrl-C to stop."
    kubectl port-forward "service/${RABBITMQ_CLUSTER}" -n "$NAMESPACE" 15672:15672
    ;;
  queues)
    kubectl exec -n "$NAMESPACE" "$POD" -- \
      rabbitmqadmin list queues \
        name messages messages_ready messages_unacknowledged consumers state
    ;;
  exchanges)
    kubectl exec -n "$NAMESPACE" "$POD" -- \
      rabbitmqadmin list exchanges \
        name type durable auto_delete
    ;;
  bindings)
    kubectl exec -n "$NAMESPACE" "$POD" -- \
      rabbitmqadmin list bindings \
        source destination_type destination routing_key
    ;;
  *)
    echo "Unknown command: $COMMAND"
    echo "Usage: rabbitmq-admin.sh <ui|queues|exchanges|bindings> [cluster_name]"
    exit 1
    ;;
esac
