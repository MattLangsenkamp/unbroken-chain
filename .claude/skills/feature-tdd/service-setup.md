# Service Setup Reference

Read this **only when creating a brand-new service from scratch**. If you are adding a feature to an existing service, the service module is already laid out and the layer templates in `layers/` are sufficient — do not load this file.

A service is a top-level directory next to the existing services in the repo (e.g. alongside `provider-gateways/github-gateway/`, `reader/`, `writer/`, `extraction-service/`, `ubc-control-plane/`). Setting one up is a one-time act: create the module skeleton, register it in `build.mill`, and add the deployment glue. After that, every feature inside it is built via the inside-out TDD layer templates.

For the architectural rules around ports and adapters (port trait conventions, adapter naming, in-memory stubs, module wiring diagram), see the `ports-and-adapters` skill.

---

## Module layout (per service)

```
<service>/
  domain/
    domainPublic/                          # types shared cross-service / cross-platform (JVM + JS)
    domainPrivate/                         # types internal to this service (JVM only)
    domainPublicAdapterExtensions/
      <tech>/                              # codec/schema modules per infra (zio-json, magnum, ...)
    domainPrivateAdapterExtensions/
      <tech>/
  core/
    ports/                                 # port traits — domain types + ZIO effect types only
    core-impl/                             # service business logic (depends only on ports)
    adapters/
      <tech>-<name>/                       # driven adapter (e.g. magnum-token-repository)
      in-memory-<name>/                    # in-memory stub for tests/local dev
  api/
    api-defn/                              # semantic API contract trait — pure Scala signatures, no Tapir
    internal-api-adapters/
      <tech>/                              # driving adapter (e.g. http for Tapir + zio-http)
    external-api-adapters/
      <tech>/                              # typed clients other services / browsers use
  presentation/                            # optional Tyrian SPA — see `presentation` skill
  server/                                  # the only place that wires layers via .provide(...)
  k8s/                                     # Helm chart — see the k8s skill
  db-migrations/                           # only if the service has a database
```

Cross-service infrastructure lives in `common/<module-name>/` (e.g. `common/db-test-support`, `common/hikari-magnum-transactor`, `common/tapir-tracing-interceptor`, `common/activity-logging`).

A module belongs in `common` only if at least two services need it identically. Domain types, business logic, and service-specific adapters do not.

---

## Dependency direction

Each module's `moduleDeps` follows this graph (arrows = "depends on"):

```
domainPublic                    domainPrivate
     ↑    ↑                          ↑
api/api-defn   core/ports             |
     ↑              ↑                 ↑
     |         core/adapters/<tech>-<name>
     ↑                    ↑
api/internal-api-adapters/<tech>
              ↑
           server
```

Hard rules:
- `api/api-defn` depends only on `domainPublic` — never on `domainPrivate`
- `core/ports` may use `domainPublic` and `domainPrivate`, never infrastructure
- `core/adapters/<tech>-<name>` depends on `core/ports` plus its infra deps; adapters never depend on each other
- `api/internal-api-adapters/<tech>` depends on `api/api-defn` and `core/core-impl`, never on ports or driven adapters directly
- `server` is the only module that picks which adapters to wire

---

## ZIO Service pattern

Services in `core/core-impl` are plain `case class`es taking their port dependencies via constructor. The companion exposes a `ZLayer`:

```scala
case class GitHubOrgService(orgRepo: OrgRepository, githubClient: GitHubPort):
  def linkOrg(userId: UserId, orgName: OrgName): IO[DomainError, GitHubOrg] = ???

object GitHubOrgService:
  val layer: URLayer[OrgRepository & GitHubPort, GitHubOrgService] =
    ZLayer.fromFunction(GitHubOrgService.apply)
```

No backing trait, no `Live` suffix, no accessor methods on the companion (the accessor pattern was dropped from ZIO's recommended practices). Callers that need the service either inject it via constructor or use `ZIO.serviceWithZIO[GitHubOrgService]` at the call site.

For the TDD patterns at each layer, see:
- Domain types → `layers/domain.md`
- Port traits + in-memory stubs → `layers/ports.md`
- Core service logic → `layers/core.md` (every method opens with a `ZIO.logActivity(...)` call)
- Driven adapters (Magnum, Tapir, etc.) → `layers/driven-adapter.md` and its `adapter-examples/`
- Driving adapters (HTTP, message queues, etc.) → `layers/driving-adapter.md` and its `adapter-examples/`
- Adapter-extension modules (zio-json, magnum codec wiring) → `layers/adapter-extension-examples/`
- Configuration → `layers/config.md`

---

## Mill build conventions

- File is `build.mill` (Mill 1.x), not `build.sc`
- External deps use `mvn"..."` and are grouped into top-level `Seq[Dep]` vals (`zioDeps`, `magnumDeps`, `tapirServerDeps`, etc.)
- Each service is a top-level `object <service> extends Module` containing nested `ScalaModule` / `ScalaJSModule` definitions
- A single `val scalaVer` is defined once at the top of `build.mill` and reused everywhere
- Test modules use `object test extends ScalaTests` with `def testFramework = "zio.test.sbt.ZTestFramework"`

A `ServiceModule` trait is already defined in `build.mill` for the JVM backend. A new service's `server` extends it:

```scala
object server extends ServiceModule {
  override def moduleDeps = Seq(
    core.`core-impl`,
    core.adapters.`<each-driven-adapter>`,
    api.`internal-api-adapters`.http,
    common.`hikari-magnum-transactor`,   // only if DB-backed
    common.`base-telemetry`,
    common.`server-utils`,
    common.`cors-middleware`
  )
  object docker extends ServiceDockerConfig {
    def tags = List("unbrokenchain/<service>:latest")
  }
}
```

For test infrastructure specifics (Testcontainers, `TestDatabase.suiteLayer`, Tapir stub interpreter), see the relevant `feature-tdd` layer template.

---

## Server entry point

`server/` is a `ZIOAppDefault`. The only place where `provide(...)` or `ZLayer.make` is called.

```scala
object Server extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase)

  def run = appProgram.provide(
    GitHubGatewayHttpController.layer,
    GitHubOrgService.layer,
    MagnumOrgRepository.layer,
    HikariMagnumTransactor.layer,
    ServerLayers.serverAfterTelemetry,
    BaseTelemetry.live("<service>")
    // ... more layers
  )
```

`Server.scala` is wiring only — no business logic, no anonymous case classes, no inline transformations of config or services. Even **layer definitions** belong with the service or adapter they construct (in the companion object of `MyService` / `MyAdapter`), not here. The server's job is to import those companion-object layers, list them inside `provide(...)`, and run the program. If you find yourself defining a layer inside `Server.scala`, move it next to the thing it builds and import it.

Background fibers (sweepers, schedulers, queue consumers) are forked **inside the service** that owns them, exposed as a layer from that service's companion. The server provides that layer alongside the others — it does not fork the fiber itself. This keeps the lifetime of the fiber colocated with the service it depends on, and makes it testable in isolation:

```scala
object GitHubGatewayService:
  // The service's normal layer.
  val layer: URLayer[<deps>, GitHubGatewayService] =
    ZLayer.fromFunction(GitHubGatewayService.apply)

  // A scoped layer that forks the sweeper for this service's lifetime.
  val sweeperLayer: ZLayer[GitHubGatewayService & GitHubGatewayConfig, Nothing, Unit] =
    ZLayer.scoped {
      for
        cfg <- ZIO.service[GitHubGatewayConfig]
        svc <- ZIO.service[GitHubGatewayService]
        _   <- svc.sweepExpiredFlows()
                 .repeat(Schedule.spaced(cfg.sweepInterval))
                 .forkScoped
      yield ()
    }
```

Then `Server.scala` simply lists `GitHubGatewayService.sweeperLayer` in its `provide(...)`.

For env-var conventions and the `derives Config` pattern, see `layers/config.md`.

---

## Deployment glue (one-time per service)

After the Mill module is wired and compiles, register the new service with the rest of the local-dev / deployment pipeline. Each step is described in detail in its own skill — skim those before editing:

- **Helm chart** → see the `k8s` skill. Every backend service has a `k8s/Chart.yaml` + `templates/` mirroring an existing service.
- **Image loading** → add the new image to `bin/load-images.sh`'s `IMAGES` array.
- **Local deploy** → add `deploy_service <name> "$REPO_ROOT/<service>/k8s"` to `bin/deploy-local.sh`.
- **Make targets** → add the build/load/import targets for the JVM image. Follow the existing pattern in `Makefile`. See the `make-utils` skill.
- **Frontend (if any)** → see the `presentation` skill. SPAs need their own `package.json`, `Dockerfile`, `nginx.conf`, and Helm chart inside `<service>/presentation/`.
- **Database (if any)** → see the `relational-database-modeling` skill. Add a `db-migrations/resources/db/migration/V1__<service>_baseline.sql` and a `db-migrations` JavaModule in `build.mill`.

---

## Conventions for the rest

- Configs live in `domainPrivate` and use `zio-config-magnolia`'s `derives Config`. See `layers/config.md`.
- Codecs (`JsonCodec`, `DbCodec`, Tapir `Schema`) NEVER live in domain types; they belong in adapter-extension modules. See `layers/domain.md` and `layers/adapter-extension-examples/`.
- Activity logging is mandatory at the start of every core service method. See `layers/core.md`.
