# Adding a New Service

Follow these steps every time a new service is added to the platform. All five steps are required — missing any one of them will leave the service partially wired.

---

## 1. Add the Mill module to `build.mill`

Every service must be defined in `build.mill` before it gets a Helm chart. Follow the existing pattern:

```scala
object `my-service` extends Module {
  object server extends ServiceModule {
    object docker extends ServiceDockerConfig {
      def tags = List("unbrokenchain/my-service:latest")
    }
  }
}
```

The Mill module name determines the service directory name. Use kebab-case.

---

## 2. Create the service directory and source stub

```bash
mkdir -p my-service/server/src
```

Add a minimal `Server.scala` stub so the module compiles:

```scala
package ubc.myservice.server

import zio.*

object Server extends ZIOAppDefault:
  def run = ZIO.logInfo("my-service starting").repeat(Schedule.fixed(5.seconds))
```

---

## 3. Create the service Helm chart

```bash
mkdir -p my-service/k8s/templates
```

**`my-service/k8s/Chart.yaml`**
```yaml
apiVersion: v2
name: my-service
description: My service
type: application
version: 0.1.0
appVersion: latest
```

**`my-service/k8s/values.yaml`** — copy verbatim from any existing service chart and adjust `postgres.enabled`:
```yaml
replicaCount: 1

image:
  repository: ""
  tag: latest
  pullPolicy: IfNotPresent

postgres:
  enabled: false   # set true if this service needs Postgres
  host: unbroken-chain-pg-rw
  db: ""
  user: app

rabbitmq:
  host: unbroken-chain-rabbitmq
  user: user

otel:
  endpoint: "http://otel-collector:4317"

extraEnv: []
resources: {}
```

**`my-service/k8s/.helmignore`** — copy from any existing service chart (the content is identical for all services).

**`my-service/k8s/templates/`** — copy `deployment.yaml`, `service.yaml`, and `configmap.yaml` from any existing service chart. The templates are identical across all services; no customisation is needed.

---

## 4. Wire into the umbrella chart

### `umbrella/Chart.yaml` — add the dependency

```yaml
dependencies:
  # ... existing entries ...
  - name: my-service
    version: "0.1.0"
    repository: "file://../my-service/k8s"
```

### `umbrella/values.yaml` — add the sub-chart values block

```yaml
my-service:
  replicaCount: 1
  image:
    repository: unbrokenchain/my-service
    tag: latest
    pullPolicy: IfNotPresent
  postgres:
    enabled: false   # flip to true and fill in db/user if Postgres is needed
  resources:
    limits:
      memory: "256Mi"
      cpu: "250m"
```

If the service needs **Postgres**, also:

1. Set `postgres.enabled: true` and fill in `db` and `user` in the block above
2. Add an entry to `umbrella/values.yaml` under `postgres.databases`:

```yaml
postgres:
  databases:
    # ... existing entries ...
    - name: my-service-db
      dbName: my_service
```

This causes `umbrella/templates/databases.yaml` to render a `Database` CR for the service automatically.

---

## 5. Update `bin/deploy-local.sh` if needed

The deploy script does not need changes for most services. The umbrella chart's `helm upgrade --install` picks up new sub-charts automatically after `helm dependency update`.

If the service requires a new operator, follow `references/adding-an-operator.md` first.

---

## Checklist

- [ ] Mill module added to `build.mill`
- [ ] Source directory and stub `Server.scala` created
- [ ] `my-service/k8s/` chart created (Chart.yaml, values.yaml, .helmignore, templates/)
- [ ] Dependency added to `umbrella/Chart.yaml`
- [ ] Sub-chart values added to `umbrella/values.yaml`
- [ ] If Postgres: `postgres.enabled: true` set and entry added to `postgres.databases`
- [ ] `helm dependency update umbrella/` runs cleanly
