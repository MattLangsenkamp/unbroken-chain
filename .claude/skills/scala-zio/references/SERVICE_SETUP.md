# Service Setup Reference

Lightweight overview of how a service is laid out. For implementing a feature inside a service (which is most of the work), see the **`feature-tdd`** skill — its layer templates contain the concrete patterns and build.mill snippets for each layer.

For the architectural rules around ports and adapters (port trait conventions, naming, in-memory stubs, module wiring diagram), see the **`ports-and-adapters`** skill.

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
    api-defn/                              # Tapir endpoint definitions (depends only on domainPublic)
    internal-api-adapters/
      <tech>/                              # driving adapter (e.g. http for Tapir + zio-http)
  server/                                  # the only place that wires layers via .provide(...)
  k8s/                                     # Helm chart — see the k8s skill
```

Cross-service infrastructure lives in `common/<module-name>/` (e.g. `common/db-test-support`, `common/hikari-magnum-transactor`, `common/tapir-tracing-interceptor`).

A module belongs in `common` only if at least two services need it identically. Domain types, business logic, and service-specific adapters do not.

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

For port traits, in-memory stubs, and adapter implementations, see the **`feature-tdd`** layer templates:
- Port traits + in-memory stubs → `feature-tdd/layers/ports.md`
- Core service logic → `feature-tdd/layers/core.md`
- Driven adapters (Magnum, Tapir, etc.) → `feature-tdd/layers/driven-adapter.md` and its `adapter-examples/`
- Driving adapters (HTTP, message queues, etc.) → `feature-tdd/layers/driving-adapter.md` and its `adapter-examples/`
- Adapter-extension modules (zio-json, magnum codec wiring) → `feature-tdd/layers/adapter-extension-examples/`

## Mill build conventions

- File is `build.mill` (Mill 1.x), not `build.sc`
- External deps use `mvn"..."` and are grouped into top-level `Seq[Dep]` vals (`zioDeps`, `magnumDeps`, `tapirServerDeps`, etc.)
- Each service is a top-level `object <service> extends Module` containing nested `ScalaModule` / `ScalaJSModule` definitions
- A single `val scalaVer` is defined once at the top of `build.mill` and reused everywhere
- Test modules use `object test extends ScalaTests` with `def testFramework = "zio.test.sbt.ZTestFramework"`

For test infrastructure specifics (Testcontainers, `TestDatabase.suiteLayer`, Tapir stub interpreter), see the relevant `feature-tdd` layer template.

## Server entry point

`server/` is a `ZIOAppDefault`. The only place where `provide(...)` or `ZLayer.make` is called.

```scala
object Server extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase.upperCase)

  def run = appProgram.provide(
    Server.default,
    GitHubOrgService.layer,
    MagnumOrgRepository.layer,
    HikariMagnumTransactor.layer,
    // ... more layers
  )
```

Background fibers are forked here, never inside service implementations.

## Config

Config types live in `domainPrivate` and use `zio-config-magnolia`:

```scala
final case class MyConfig(queueUrl: String, timeoutMs: Long) derives Config

object MyConfig:
  val layer: TaskLayer[MyConfig] = ZLayer.fromZIO(ZIO.config[MyConfig])
```

See `references/CONFIGURATION.md` for env var naming and the snake_case→camelCase rules.
