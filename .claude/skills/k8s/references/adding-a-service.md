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

## 6. Add Flyway migrations (if Postgres-enabled)

Create a `db-migrations/` folder alongside the service:

```
my-service/
  db-migrations/
    Dockerfile
    resources/
      db/
        migration/
          V1__initial.sql
```

**`my-service/db-migrations/Dockerfile`**:
```dockerfile
FROM flyway/flyway:latest
COPY db-migrations/resources/db/migration/ /flyway/sql/
```

Note: the Docker build context must be the **service root** (not `db-migrations/`) so the `COPY` path resolves correctly. `bin/build-migrations.sh` handles this automatically.

Add a Mill module for the migrations inside the service in `build.mill` so SQL files land on the test classpath:
```scala
object `my-service` extends Module {
  object `db-migrations` extends JavaModule {
    override def resources = Task.Sources(moduleDir / "resources")
  }
  // ...
}
```

Wire it into your repository adapter's test module:
```scala
object test extends ScalaTests {
  override def moduleDeps = super.moduleDeps ++ Seq(
    common.`db-test-support`,
    `db-migrations`
  )
}
```

And use `classpath:db/migration` as the migration location in your test spec:
```scala
private val migrationLocation = "classpath:db/migration"
```

Add the `migrations` block to `my-service/k8s/values.yaml`:
```yaml
migrations:
  image:
    repository: "unbrokenchain/my-service-migrations"
    tag: latest
    pullPolicy: IfNotPresent
```

Copy `migrate-job.yaml` from any existing Postgres-enabled service into `my-service/k8s/templates/`. The template is identical across services.

Add a call to `bin/prepare-migrations` in `Makefile`:
```makefile
prepare-migrations:
    @$(SCRIPT_DIR)/build-migrations.sh my-service unbrokenchain/my-service-migrations:latest $(CLUSTER_NAME)
```

The Helm hook Job in `migrate-job.yaml` fires as `pre-install,pre-upgrade` — migrations run on every deploy but never on pod restarts. Flyway is idempotent; re-running against an up-to-date schema is safe.

---

---

## Adding a Presentation to a Service

If the service needs a Tyrian SPA frontend, follow these additional steps. See `.claude/skills/presentation/SKILL.md` for full details.

### 1. Scaffold the presentation directory

Copy from an existing presentation (e.g. `ubc-control-plane/presentation/`) and update:
- `index.html` — update `<title>`
- `package.json` — update `"name"`
- `k8s/Chart.yaml` — update `name` and `description`
- `k8s/values.yaml` — set `image.repository: "unbrokenchain/my-service-presentation"`

```bash
cp -r ubc-control-plane/presentation my-service/presentation
# then edit the files above
```

### 2. Add the Mill module

In `build.mill`, add `object presentation extends PresentationModule` to the service:

```scala
object `my-service` extends Module {
  object presentation extends PresentationModule
  object server extends ServiceModule { ... }
}
```

### 3. Wire into deploy and images

**`bin/deploy-local.sh`** — add after the backend deploy_service call:
```bash
deploy_service my-service-presentation "$REPO_ROOT/my-service/presentation/k8s"
```

**`bin/load-images.sh`** — add to `IMAGES`:
```bash
"unbrokenchain/my-service-presentation:latest"
```

**`Makefile`** — add variable and targets:
```makefile
MY_SERVICE_PRESENTATION_IMAGE ?= unbrokenchain/my-service-presentation:latest

## build-my-service-presentation : build the my-service presentation nginx image
build-my-service-presentation:
	@$(SCRIPT_DIR)/build-presentation.sh my-service/presentation $(MY_SERVICE_PRESENTATION_IMAGE) $(CLUSTER_NAME)
```

Also add to `build-images` and `build-presentations`.

---

## Checklist

- [ ] Mill module added to `build.mill`
- [ ] Source directory and stub `Server.scala` created
- [ ] `my-service/k8s/` chart created with `image.repository` set
- [ ] `deploy_service` call added to `bin/deploy-local.sh` step 5
- [ ] If Postgres: `postgres.enabled: true` and entry in `umbrella/values.yaml` `postgres.databases`
- [ ] If Postgres: `my-service/db-migrations/` created with `Dockerfile` and initial migration under `resources/db/migration/`
- [ ] If Postgres: `migrations` block added to `values.yaml` and `migrate-job.yaml` copied into templates
- [ ] If Postgres: `build-migrations.sh` call added to `prepare-migrations` in Makefile
- [ ] If presentation: scaffold `my-service/presentation/` from existing, update title/name/image
- [ ] If presentation: `object presentation extends PresentationModule` in `build.mill`
- [ ] If presentation: `deploy_service` and image wired into deploy/load/Makefile
