# Service Setup Reference

This document covers conventions for structuring services in this project.

---

## Mill Build Conventions

### Dependency Groups

Declare all external dependencies as top-level `val`s of `Agg[Dep]` and compose them per module. Never inline individual deps inside module definitions.

```scala
val zioDeps = Agg(
  ivy"dev.zio::zio:2.1.17",
  ivy"dev.zio::zio-streams:2.1.17"
)

val zioHttpDeps = Agg(
  ivy"dev.zio::zio-http:3.0.1"
)
```

### Service Module Structure

Each service is a top-level `object extends Module` (not `ScalaModule`). This namespaces the submodules without making the top-level object compilable itself.

```scala
object myService extends Module {
  object domainPublic extends ScalaModule { ... }
  object domainPrivate extends ScalaModule { ... }
  object api extends ScalaModule { ... }
  object services extends ScalaModule { ... }
  object server extends ScalaModule { ... }
}
```

### Dependency Direction

The enforced dependency graph (via `moduleDeps`) is:

```
domainPublic
    ↑         ↑
domainPrivate  api
    ↑         ↑
       services
          ↑
        server
```

- `domainPublic` depends on nothing in the service
- `domainPrivate` and `api` depend on `domainPublic`
- `services` depends on `domainPublic`, `domainPrivate`, and `api`
- `server` depends on all of the above

Cross-service dependencies (e.g. one service consuming another's `domainPublic`) are declared explicitly in `moduleDeps`.

### Centralized Scala Version

Always use a shared `val scalaVer` defined once at the top of `build.sc`. Never hardcode the version per module.

---

## Directory Structure and Purposes

Each service follows this layout:

```
<service>/
  domainPublic/src/    package: app.<service>.domain
  domainPrivate/src/   package: app.<service>.domain.internal
  api/src/             package: app.<service>.api
  services/src/        package: app.<service>.services
  server/src/          package: app.<service>.server
  k8s/                 Helm chart for this service — see the k8s skill for full details
```

Cross-service shared infrastructure lives in:

```
common/
  <module-name>/src/   package: common.<modulename>
```

### domainPublic

Types that are **safe for other services to consume** — the data shapes exchanged across networking boundaries (HTTP, SQS, Kafka, etc.). A type belongs here if another service might legitimately import and use it.

### domainPrivate

Types that **never leave the service boundary**. This includes:
- Config types loaded from environment variables
- Third-party vendor models (SQS message shapes, external API responses)
- Persistence/storage models
- Any internal data structure specific to this service's implementation

Use access modifiers to enforce this boundary where possible.

### api

**Declarative contract definitions only** — no ZIO logic, no business logic. Tapir endpoint definitions (HTTP shape: method, path, input types, output types, error types) that reference `domainPublic` types.

```scala
object MyEndpoint:
  val myEndpoint: PublicEndpoint[MyRequest, MyError, MyResponse, Any] =
    endpoint.post
      .in("my-path")
      .in(jsonBody[MyRequest])
      .errorOut(jsonBody[MyError])
      .out(jsonBody[MyResponse])
```

### services

**Service traits, Live implementations, and external integrations.** All services must follow the [ZIO Service Pattern](https://zio.dev/reference/service-pattern/). When in doubt, read the official docs first — they are the authoritative reference.

**1. Trait — the service interface**
```scala
trait MyService:
  def doThing(input: String): Task[Unit]
```

**NEVER add accessor methods to the trait's companion object.** The accessor pattern (`ZIO.serviceWithZIO` wrappers in the companion) is an anti-pattern that was dropped from ZIO's recommended practices. Callers inject the service as a constructor dependency or use `ZIO.serviceWithZIO` at the call site when needed.

```scala
// ❌ NEVER do this
object MyService:
  def doThing(input: String): ZIO[MyService, Throwable, Unit] =
    ZIO.serviceWithZIO[MyService](_.doThing(input))
```

**2. `Live` implementation — `final case class` extending the trait**

```scala
final case class MyServiceLive(dep: MyDep) extends MyService:
  override def doThing(input: String): Task[Unit] = ???
```

**3. `layer` — lifts the implementation into `ZLayer`**

Simple (no effectful initialization):
```scala
object MyServiceLive:
  val layer: URLayer[MyDep, MyService] =
    ZLayer.fromFunction(MyServiceLive.apply)
```

Effectful (e.g. allocating resources):
```scala
object MyServiceLive:
  val layer: RLayer[MyDep & Meter, MyService] =
    ZLayer.fromZIO:
      for
        dep   <- ZIO.service[MyDep]
        meter <- ZIO.service[Meter]
        _     <- meter.counter("my.metric")
      yield MyServiceLive(dep, meter)
```

### server

**The `ZIOAppDefault` entry point.** The only place where `provide(...)` or `ZLayer.make` is called. Wires all layers together, starts the HTTP server, and forks any background fibers.

---

## Code Patterns

### Service Trait + Live Implementation

**Trait (`services/src/MyService.scala`):**
```scala
package app.myservice.services

import zio.*

trait MyService:
  def doThing(input: String): Task[Unit]
```

**Live implementation (`services/src/MyServiceLive.scala`):**
```scala
package app.myservice.services

import zio.*

final case class MyServiceLive(dep1: Dep1, dep2: Dep2) extends MyService:
  override def doThing(input: String): Task[Unit] =
    ??? // implementation

object MyServiceLive:
  val layer: URLayer[Dep1 & Dep2, MyService] =
    ZLayer.fromFunction(MyServiceLive.apply)
```

### Config Pattern

Config types live in `domainPrivate` and use `zio-config-magnolia` for automatic derivation. Never use `System.env` directly — always go through `ZIO.config`.

```scala
package app.myservice.domain.internal

import zio.config.*
import zio.config.magnolia.*

final case class MyConfig(queueUrl: String, timeoutMs: Long) derives Config

object MyConfig:
  val layer: TaskLayer[MyConfig] =
    ZLayer.fromZIO(ZIO.config[MyConfig])
```

Wire the config provider once in `server`:
```scala
override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
  Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase.upperCase)
```

See `references/CONFIGURATION.md` for full details and field naming rules.

### Server Entry Point

```scala
package app.myservice.server

import zio.*
import zio.http.*

object Server extends ZIOAppDefault:

  private val app: Routes[MyService, Response] = ???  // wired from api + services

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    Server.serve(app).provide(
      Server.default,
      MyConfig.layer,
      MyServiceLive.layer
    )
```

Background jobs are forked in `run`, not inside service implementations.

### Health Endpoint (api module)

Every service has a health endpoint declared in `api`:

```scala
package app.myservice.api

import sttp.tapir.*

object HealthEndpoint:
  val healthEndpoint: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("health")
      .out(stringBody)
```

---

## Repository / DB Adapter Pattern

Repositories are driven adapters that implement a port trait and live in `core/adapters/<library>-<name>-repository`.

### Prefer domain types directly

**Do not introduce a row model unless the DB schema genuinely cannot be expressed with domain types.** Magnum's `DbCodec.derived` works for any case class as long as all field types have `DbCodec` instances in scope. Provide those instances in the domain's adapter-extension module (e.g. `domainPublicAdapterExtensions/magnum`, `domainPrivateAdapterExtensions/magnum`).

```scala
// ✅ Preferred — derive directly on the domain type
private given DbCodec[MyEntity] = DbCodec.derived[MyEntity]

class MyRepository(xa: TransactorZIO) extends MyPort:
  def findById(id: MyId): Task[Option[MyEntity]] =
    xa.connect {
      sql"SELECT ... FROM my_table WHERE id = $id".query[MyEntity].run().headOption
    }
```

### When a row model IS needed

If the DB schema diverges from the domain model (e.g. normalised columns for a collection stored as a delimited string, or a legacy schema), introduce a minimal row case class in the adapter and use [Chimney](https://github.com/scalalandio/chimney) for the domain↔row transformation:

```scala
import io.scalaland.chimney.dsl.*

case class MyEntityRow(id: Long, name: String, tags: String) derives DbCodec

object MyEntityRow:
  def fromDomain(e: MyEntity): MyEntityRow = e.into[MyEntityRow]
    .withFieldComputed(_.tags, _.tags.mkString(","))
    .transform

  extension (row: MyEntityRow)
    def toDomain: MyEntity = row.into[MyEntity]
      .withFieldComputed(_.tags, _.tags.split(",").toList.filter(_.nonEmpty))
      .transform
```

### ZIO integration (Magnum 2.x)

Use `TransactorZIO` from `com.augustnagro::magnumzio`. Inject it via `ZLayer`:

```scala
object MyRepository:
  val layer: ZLayer[TransactorZIO, Nothing, MyPort] =
    ZLayer.fromFunction(new MyRepository(_))
```

Wire `TransactorZIO.layer` in the `server` module, which itself needs a `DataSource` (HikariCP).
