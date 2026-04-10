---
name: k8s
description: Kubernetes deployment conventions for this project. Covers the infra Helm chart, per-service chart layout, operator management, secrets, and observability. Consult this when adding services, adding operators, or modifying the deployment setup.
allowed-tools: Bash
---

# Kubernetes / Helm Conventions

Services and infra are deployed separately. The **infra chart** (`umbrella/`) owns all platform-level resources (databases, messaging, observability, secrets). **Each service chart** is deployed directly by `bin/deploy-local.sh` via its own `helm upgrade --install` call — services are NOT wired as umbrella chart dependencies.

## Reference Files

| Reference | When to use it |
|---|---|
| [`references/adding-a-service.md`](references/adding-a-service.md) | Adding a new service — Mill module, k8s chart, deploy script wiring |
| [`references/adding-an-operator.md`](references/adding-an-operator.md) | Adding a new operator to the platform |

## Key Conventions

- **One Helm chart per service**, living at `<service>/k8s/` alongside the Scala source
- **`image.repository` must be set** in each service's `k8s/values.yaml` — there is no umbrella values override. Use `unbrokenchain/<service-name>`.
- **Infra chart** at `umbrella/` is infra-only: Postgres cluster, RabbitMQ cluster, OTel collector, ESO secret store + ExternalSecrets. It has no service chart dependencies.
- **Operators are never bundled** as Helm chart dependencies — installed exclusively by `bin/deploy-local.sh` step 2
- **RabbitMQ Cluster Operator** is installed via `kubectl apply` (official manifest), not Helm — bitnami images are not available on Docker Hub
- **Credentials** flow through ESO: `local-credentials` Secret → `ClusterSecretStore` → `ExternalSecret` → service-specific Secret. `local-credentials` must include `postgres-username`, `postgres-password`, and `rabbitmq-password`.
- **`make deploy-app`** runs `bin/deploy-local.sh` (idempotent, safe to re-run)
- **`make k9s`** opens k9s across all namespaces

## API Versions in Use

| Resource | apiVersion |
|---|---|
| `ClusterSecretStore` | `external-secrets.io/v1` |
| `ExternalSecret` | `external-secrets.io/v1` |
| `OpenTelemetryCollector` | `opentelemetry.io/v1beta1` (v1alpha1 is deprecated) |
| `RabbitmqCluster` | `rabbitmq.com/v1beta1` |
| `Cluster` (CNPG) | `postgresql.cnpg.io/v1` |

## Directory Layout

```
umbrella/                        # infra chart — no service dependencies
  Chart.yaml
  values.yaml                    # postgres / rabbitmq / otel / eso config only
  templates/
    postgres-cluster.yaml        # CloudNativePG Cluster CR
    databases.yaml               # Database CR per Postgres-backed service
    rabbitmq-cluster.yaml        # RabbitmqCluster CR
    otel-collector.yaml          # grafana/otel-lgtm Deployment + OpenTelemetryCollector CR
    eso-secret-store.yaml        # ClusterSecretStore + RBAC
    external-secrets.yaml        # ExternalSecret per credential

<service>/
  k8s/                           # Helm chart for the JVM backend
    Chart.yaml
    values.yaml                  # image.repository must be set here
    templates/
      deployment.yaml
      service.yaml
      configmap.yaml
  presentation/                  # Tyrian SPA (only on services that have a frontend)
    k8s/
      Chart.yaml
      values.yaml                # image.repository: unbrokenchain/<service>-presentation
      templates/
        deployment.yaml          # nginx on port 80, no env vars
        service.yaml             # ClusterIP port 80
```

## deploy-local.sh — Order of Operations

1. Pull and import `grafana/otel-lgtm` into k3d
2. Install/upgrade operators (CloudNativePG, RabbitMQ, OTel, ESO) — wait for each
3. Create namespace and `local-credentials` Secret if absent
4. `helm upgrade --install unbroken-chain-infra umbrella/`
5. `helm upgrade --install <name> <service>/k8s/` for each service

## Installed Operators

| Operator | Install method | Source |
|---|---|---|
| CloudNativePG | `helm upgrade --install` | `https://cloudnative-pg.github.io/charts` |
| RabbitMQ Cluster Operator | `kubectl apply -f` | GitHub release manifest |
| OpenTelemetry Operator | `helm upgrade --install` | `https://open-telemetry.github.io/opentelemetry-helm-charts` |
| External Secrets Operator | `helm upgrade --install` | `https://charts.external-secrets.io` |

> **Note:** After upgrading the OTel operator, the deploy script restarts the operator deployment to force webhook cert regeneration. This is required — without it, subsequent deploys fail with TLS cert errors.

## Services and Postgres

| Service | Chart location | Postgres | Presentation |
|---|---|---|---|
| `provider-gateways/github-gateway` | `provider-gateways/github-gateway/k8s` | ✅ (`github_gateway`) | ✅ |
| `ubc-control-plane` | `ubc-control-plane/k8s` | ✅ (`ubc_control_plane`) | ✅ |
| `reader` | `reader/k8s` | ❌ | ❌ |
| `writer` | `writer/k8s` | ❌ | ❌ |
| `extraction-service` | `extraction-service/k8s` | ❌ | ❌ |

Presentation Helm charts (`<service>/presentation/k8s/`) are deployed alongside the backend chart in `bin/deploy-local.sh`. They are minimal — no configmap, no secrets, no env vars.
