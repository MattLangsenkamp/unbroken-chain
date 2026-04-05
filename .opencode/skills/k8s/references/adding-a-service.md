# Adding a New Service

Follow these steps every time a new service is added to the platform.

---

## 1. Add the Mill module to `build.mill`

```scala
object `my-service` extends Module {
  object server extends ServiceModule {
    object docker extends ServiceDockerConfig {
      def tags = List("unbrokenchain/my-service:latest")
    }
  }
}
```

---

## 2. Create the service directory and source stub

```bash
mkdir -p my-service/server/src
```

Add a minimal `Server.scala` stub so the module compiles.

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

**`my-service/k8s/values.yaml`** — copy from any existing service chart. Set `image.repository` and `postgres.enabled`:
```yaml
replicaCount: 1

image:
  repository: "unbrokenchain/my-service"   # REQUIRED — must be set here
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

**`my-service/k8s/.helmignore`** — copy from any existing service chart.

**`my-service/k8s/templates/`** — copy `deployment.yaml`, `service.yaml`, and `configmap.yaml` from any existing service chart. Templates are identical across all services.

---

## 4. Wire into `bin/deploy-local.sh`

Add a `deploy_service` call in step 5:

```bash
deploy_service my-service "$REPO_ROOT/my-service/k8s"
```

---

## 5. Wire Postgres (if needed)

If the service needs Postgres:

1. Set `postgres.enabled: true` and fill in `db` in `my-service/k8s/values.yaml`
2. Add a database entry to `umbrella/values.yaml` under `postgres.databases`:

```yaml
postgres:
  databases:
    - name: my-service-db
      dbName: my_service
```

This causes `umbrella/templates/databases.yaml` to render a `Database` CR automatically.

---

## Checklist

- [ ] Mill module added to `build.mill`
- [ ] Source directory and stub `Server.scala` created
- [ ] `my-service/k8s/` chart created with `image.repository` set
- [ ] `deploy_service` call added to `bin/deploy-local.sh` step 5
- [ ] If Postgres: `postgres.enabled: true` and entry in `umbrella/values.yaml` `postgres.databases`
