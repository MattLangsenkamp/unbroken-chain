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
| `Middleware` (Traefik) | `traefik.io/v1alpha1` |
| `Ingress` | `networking.k8s.io/v1` |

## Ingress / Routing

All external traffic enters via the k3d Traefik loadbalancer at `localhost:8080` (→ cluster port 80). No service is directly exposed; everything goes through Ingress.

### Local URL table

| URL | Service | Notes |
|---|---|---|
| `http://ubc.localhost:8080` | `ubc-control-plane-presentation` | Primary SPA |
| `http://github.localhost:8080` | `github-gateway-presentation` | Secondary SPA |
| `http://api.localhost:8080/control-plane/` | `ubc-control-plane` | prefix stripped |
| `http://api.localhost:8080/github-gateway/` | `github-gateway` | prefix stripped |
| `http://api.localhost:8080/reader/` | `reader` | prefix stripped |
| `http://localhost:3000` | Grafana | bypasses Traefik (direct k3d LoadBalancer) |

`.localhost` domains resolve to `127.0.0.1` in modern browsers without `/etc/hosts` edits.

### Pattern: SPA Ingress (host-based, no middleware)

SPAs use host-based routing so browsers resolve assets from the SPA's own origin — path-based routing would break absolute asset paths like `/assets/main.js`.

```yaml
# values.yaml
ingress:
  enabled: true
  host: myservice.localhost
```

```yaml
# templates/ingress.yaml
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ .Chart.Name }}
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: {{ .Values.ingress.host }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ .Chart.Name }}
                port:
                  number: 80
{{- end }}
```

### Pattern: Backend API Ingress (path-based with StripPrefix)

Backends use path-based routing on `api.localhost`. The prefix is stripped by a Traefik `Middleware` CRD before the request reaches the service, so the backend sees paths relative to `/`.

The `Middleware` CRDs live in the **umbrella chart** (`umbrella/templates/traefik-middlewares.yaml`) so they exist before any service Ingress references them (umbrella deploys in step 4, services in step 5).

```yaml
# values.yaml
ingress:
  enabled: true
  host: api.localhost
  path: /myservice/
```

```yaml
# templates/ingress.yaml
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ .Chart.Name }}
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
    traefik.ingress.kubernetes.io/router.middlewares: "{{ .Release.Namespace }}-strip-myservice-prefix@kubernetescrd"
spec:
  ingressClassName: traefik
  rules:
    - host: {{ .Values.ingress.host }}
      http:
        paths:
          - path: {{ .Values.ingress.path }}
            pathType: Prefix
            backend:
              service:
                name: {{ .Chart.Name }}
                port:
                  number: 8080
{{- end }}
```

When adding a new backend service that needs external access:
1. Add a `Middleware` CR to `umbrella/templates/traefik-middlewares.yaml`
2. Add `ingress.enabled/host/path` to the service's `values.yaml`
3. Add `templates/ingress.yaml` using the pattern above with the correct middleware name

Middleware name format: `{{ .Release.Namespace }}-strip-<service>-prefix@kubernetescrd`

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
    traefik-middlewares.yaml     # StripPrefix Middleware CRDs for backend path routing

<service>/
  k8s/                           # Helm chart for the JVM backend
    Chart.yaml
    values.yaml                  # image.repository + ingress block
    templates/
      deployment.yaml
      service.yaml
      configmap.yaml
      ingress.yaml               # guarded by .Values.ingress.enabled
  presentation/                  # Tyrian SPA (only on services that have a frontend)
    k8s/
      Chart.yaml
      values.yaml                # image.repository + ingress block
      templates/
        deployment.yaml          # nginx on port 80, no env vars
        service.yaml             # ClusterIP port 80
        ingress.yaml             # guarded by .Values.ingress.enabled
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
