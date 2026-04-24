# Feature TDD Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the `feature-tdd` skill — an orchestration skill with per-layer sub-agent templates that implements full vertical slices inside-out using TDD.

**Architecture:** One `SKILL.md` for orchestration logic (planning phase, execution loop, audit criteria). Five layer template files in `layers/` — each a self-contained sub-agent prompt embedding abbreviated TDD and exact test infrastructure for that layer type.

**Tech Stack:** Markdown skill files, ZIO Test, Testcontainers, Magnum, Tapir, Mill build system.

---

### Task 1: Create SKILL.md — orchestration

**Files:**
- Create: `.opencode/skills/feature-tdd/SKILL.md`

- [ ] **Step 1: Create the skill directory and SKILL.md**

```bash
mkdir -p .opencode/skills/feature-tdd/layers
```

Create `.opencode/skills/feature-tdd/SKILL.md` with this exact content:

````markdown
---
name: feature-tdd
description: Use when implementing a new feature from a spec — full vertical slice across domain types, ports, core logic, driven adapters, and driving adapters. Trigger when handed requirements or a spec for a new capability to build.
---

# Feature TDD

Implements a full vertical slice from a spec using inside-out TDD. Each layer is dispatched to a dedicated sub-agent that writes real code, runs TDD cycles, and reports findings. The orchestrator audits, compiles, commits, and revises the plan before dispatching the next layer.

**Layer order (inside-out):**
```
domain → ports + in-memory stubs → core service → driven adapters → driving adapter
```

## Phase 1: Plan (before writing any code)

Read the spec. Identify which layers this feature needs — not every feature touches all five. Save the plan to `docs/superpowers/plans/YYYY-MM-DD-<feature>.md`. Show it to the user and get confirmation before proceeding.

**Plan structure:**

```markdown
# <Feature Name> — Implementation Plan (Hypothesis)

> This is a hypothesis. It will be updated as TDD cycles reveal design decisions.

## Feature summary
[Domain types needed, ports affected, adapters to build — one paragraph]

## Layers
| # | Type | What to build | Test strategy | Known unknowns |
|---|---|---|---|---|
| 1 | domain | ... | ZIO Test unit tests | ... |
| 2 | ports | ... | In-memory stub exercises the contract | ... |
| 3 | core | ... | ZIO Test + in-memory adapters via ZLayer | ... |
| 4 | driven-adapter | ... | TestDatabase.suiteLayer + Testcontainers | ... |
| 5 | driving-adapter | ... | Tapir stub interpreter + in-memory core | ... |

## Dependency map
- Layer 2 needs from 1: [domain types]
- Layer 3 needs from 2: [port trait signatures, in-memory stub layer]
- Layer 4 needs from 2: [port trait, domain types]
- Layer 5 needs from 3: [service layer]

## Open questions
[Things that cannot be resolved until a TDD cycle runs]
```

## Phase 2: Execution loop

### Branch check (before first layer)
If on `main` or a `claude/*` branch, create a feature branch:
```bash
git checkout -b feature/<slug>
```

### Per-layer loop

For each layer in the plan, in order:

**1. Dispatch sub-agent**

Read the matching layer template:
- `layers/domain.md` — new domain types
- `layers/ports.md` — port traits + in-memory stubs
- `layers/core.md` — core service logic
- `layers/driven-adapter.md` — Magnum repos, Tapir HTTP clients
- `layers/driving-adapter.md` — HTTP controllers

Build the sub-agent prompt by combining:

```
<full contents of layers/<type>.md>

---
## Feature context
<feature summary paragraph from the plan>

## Code from previous layers
<paste the actual .scala file contents produced by all previous sub-agents>

## Plan hypothesis for this layer
<the "what to build", "test strategy", and "known unknowns" row for this layer>

## Feature branch
<branch name>
```

Dispatch using the Agent tool. The sub-agent has full write access.

**2. Audit the sub-agent's output**

Check the report and code for:
- TDD compliance: tests written before implementation, each test watched to fail
- Port contract: implementation correctly satisfies the port trait signature
- Naming: port names infra-free, adapter names describe the full tech stack (`ports-and-adapters` skill)
- Compilation: run `./mill __.compile` — must pass clean with no errors or warnings

If any check fails, re-dispatch the same layer with the specific issue described. Do not proceed to the next layer.

**3. Commit**

```bash
git add <changed files>
git commit -m "feat(<service>): <what was implemented> [layer N/M]"
```

**4. Revise the plan**

Update `docs/superpowers/plans/YYYY-MM-DD-<feature>.md`:
- Mark the completed layer with a checkmark
- Record deviations from the hypothesis and why
- Update remaining layers if dependencies changed

Show the plan diff to the user. Proceed to the next layer.
````

- [ ] **Step 2: Verify the file was created**

```bash
cat .opencode/skills/feature-tdd/SKILL.md | head -5
```
Expected: shows the frontmatter `---` and `name: feature-tdd`.

- [ ] **Step 3: Commit**

```bash
git add .opencode/skills/feature-tdd/SKILL.md
git commit -m "feat(skills): add feature-tdd orchestration skill"
```

---

### Task 2: Create layers/domain.md

**Files:**
- Create: `.opencode/skills/feature-tdd/layers/domain.md`

- [ ] **Step 1: Create layers/domain.md**

````markdown
# Domain Layer — Sub-Agent Instructions

You are implementing the domain layer of a feature. Define the new domain types this feature introduces. No infrastructure. No business logic.

## What to build

Domain types live in:
- `<service>/domainPublic/src/ubc/<service>/domain/` — types shared with the frontend or other services (cross-compiled JVM + JS)
- `<service>/domainPrivate/src/ubc/<service>/domain/internal/` — types private to this service (JVM only)

Use neotype `Newtype` for primitive wrappers:
```scala
import neotype.*

object OrgId extends Newtype[Long]
type OrgId = OrgId.Type

object OrgName extends Newtype[String]
type OrgName = OrgName.Type
```

Use `case class` for multi-field entities. Derive `JsonCodec` for types that cross HTTP boundaries:
```scala
import zio.json.JsonCodec
import java.time.Instant

case class GitHubOrg(
  id: OrgId,
  name: OrgName,
  createdAt: Instant
) derives JsonCodec
```

**Never import** Magnum, Tapir, JDBC, or any infrastructure in domain files.

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test that constructs or uses the type you are about to define.

```scala
// In domainPublic/test/src/ubc/<service>/domain/OrgDomainSpec.scala
object OrgDomainSpec extends ZIOSpecDefault:
  override def spec = suite("OrgDomainSpec")(
    test("OrgId wraps and unwraps correctly") {
      val id = OrgId(42L)
      assertTrue(id.unwrap == 42L)
    },
    test("OrgName wraps and unwraps correctly") {
      val name = OrgName("my-org")
      assertTrue(name.unwrap == "my-org")
    }
  )
```

Run: `./mill <service>.domainPublic.jvm.test`
Expected: compilation failure — type not yet defined.

**GREEN** — Define the type. Run the test again.
Expected: PASS.

**REFACTOR** — Minimum fields only. Right module (public vs private)?

Repeat for each type.

## Naming rules
- Type names reflect the domain concept, never the infrastructure: `OrgId` not `OrgDatabaseId`
- Newtypes for every primitive that has domain meaning: IDs, names, tokens, scopes

## Report back

When complete:
1. **Types defined** — name, location (public/private), what it wraps or its fields
2. **Tests written** — one line per test: what it verifies
3. **Deviations from plan** — anything that changed from the hypothesis
4. **Proposed amendments** — changes needed to the ports, core, or adapter layers
````

- [ ] **Step 2: Commit**

```bash
git add .opencode/skills/feature-tdd/layers/domain.md
git commit -m "feat(skills): add feature-tdd domain layer template"
```

---

### Task 3: Create layers/ports.md

**Files:**
- Create: `.opencode/skills/feature-tdd/layers/ports.md`

- [ ] **Step 1: Create layers/ports.md**

````markdown
# Ports Layer — Sub-Agent Instructions

You are implementing the ports layer of a feature. Your job is to:
1. Define the port trait(s) in `core/ports/`
2. Implement the in-memory stub(s) in `core/adapters/in-memory-<name>/`

## Port traits

Location: `<service>/core/ports/src/ubc/<service>/core/ports/`

```scala
package ubc.<service>.core.ports

import ubc.<service>.domain.*
import ubc.<service>.domain.internal.*
import zio.Task

trait OrgRepository:
  def save(org: GitHubOrg): Task[Unit]
  def findById(id: OrgId): Task[Option[GitHubOrg]]
  def delete(id: OrgId): Task[Unit]
```

Rules:
- Import only domain types and `zio.Task` — never Magnum, Tapir, JDBC, or any infrastructure
- Return `Task[A]` for operations that may fail
- One port per concern — do not bundle unrelated operations

## In-memory stubs

Location: `<service>/core/adapters/in-memory-<name>/src/ubc/<service>/core/adapters/inmemory/`

```scala
class InMemoryOrgRepository(store: Ref[Map[OrgId, GitHubOrg]]) extends OrgRepository:
  def save(org: GitHubOrg): Task[Unit] =
    store.update(_.updated(org.id, org))
  def findById(id: OrgId): Task[Option[GitHubOrg]] =
    store.get.map(_.get(id))
  def delete(id: OrgId): Task[Unit] =
    store.update(_.removed(id))

object InMemoryOrgRepository:
  val layer: ULayer[OrgRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[OrgId, GitHubOrg]).map(new InMemoryOrgRepository(_))
    )
```

Rules:
- Use `Ref` for mutable state
- `ULayer[PortTrait]` — no external dependencies, no possible failures
- Take `Ref` as constructor parameter so tests can inspect state if needed

## build.mill — add the in-memory adapter module

```scala
object `in-memory-org-repository` extends ScalaModule {
  def scalaVersion = scalaVer
  override def moduleDeps = Seq(ports)
  override def mvnDeps = zioDeps

  object test extends ScalaTests {
    def testFramework = "zio.test.sbt.ZTestFramework"
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps
  }
}
```

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test for one contract behaviour of the in-memory stub.

```scala
// In core/adapters/in-memory-org-repository/test/src/.../InMemoryOrgRepositorySpec.scala
object InMemoryOrgRepositorySpec extends ZIOSpecDefault:
  private val org = GitHubOrg(OrgId(1L), OrgName("test-org"), Instant.now())

  override def spec = suite("InMemoryOrgRepositorySpec")(
    test("save and findById round-trip") {
      for
        repo  <- ZIO.service[OrgRepository]
        _     <- repo.save(org)
        found <- repo.findById(OrgId(1L))
      yield assertTrue(found.contains(org))
    },
    test("findById returns None for unknown id") {
      for
        repo   <- ZIO.service[OrgRepository]
        result <- repo.findById(OrgId(99L))
      yield assertTrue(result.isEmpty)
    },
    test("delete removes the entry") {
      for
        repo    <- ZIO.service[OrgRepository]
        _       <- repo.save(org)
        _       <- repo.delete(OrgId(1L))
        afterDel <- repo.findById(OrgId(1L))
      yield assertTrue(afterDel.isEmpty)
    }
  ).provide(InMemoryOrgRepository.layer)
```

Run: `./mill <service>.core.adapters.\`in-memory-org-repository\`.test`
Expected: compilation failure — types not yet defined.

**GREEN** — Define the port trait, then the in-memory stub. Run tests again.
Expected: all PASS.

**REFACTOR** — Is the port minimal? Does the stub faithfully implement every contract case?

Repeat for each method and each port trait.

## Naming rules
- Port: infra-free name (`OrgRepository`, not `PostgresOrgRepository`)
- Stub: `InMemory<Name>` always
- Module dir: `in-memory-<kebab-name>`

## Report back

When complete:
1. **Port traits defined** — name, location, methods
2. **In-memory stubs defined** — name, location, `ZLayer` type signature
3. **Tests written** — one line per test: what contract behaviour it exercises
4. **Deviations from plan** — anything that changed
5. **Proposed amendments** — changes needed to core or adapter layers
````

- [ ] **Step 2: Commit**

```bash
git add .opencode/skills/feature-tdd/layers/ports.md
git commit -m "feat(skills): add feature-tdd ports layer template"
```

---

### Task 4: Create layers/core.md

**Files:**
- Create: `.opencode/skills/feature-tdd/layers/core.md`

- [ ] **Step 1: Create layers/core.md**

````markdown
# Core Layer — Sub-Agent Instructions

You are implementing the core service logic of a feature. Write business logic in `core/core-impl/` and test it using in-memory adapters injected via `ZLayer`. No SQL, no HTTP, no infrastructure.

## What to build

Location: `<service>/core/core-impl/src/ubc/<service>/core/`

```scala
package ubc.<service>.core

import ubc.<service>.core.ports.*
import ubc.<service>.domain.*
import ubc.<service>.domain.internal.*
import zio.*

trait GitHubOrgService:
  def linkOrg(userId: UserId, orgName: OrgName): Task[GitHubOrg]

final case class GitHubOrgServiceLive(
  orgRepo: OrgRepository
) extends GitHubOrgService:
  def linkOrg(userId: UserId, orgName: OrgName): Task[GitHubOrg] =
    // business logic only — no SQL, no HTTP
    ???

object GitHubOrgServiceLive:
  val layer: URLayer[OrgRepository, GitHubOrgService] =
    ZLayer.fromFunction(GitHubOrgServiceLive.apply)
```

Rules:
- Depends only on `core/ports` — never on Magnum, Tapir, or any adapter
- Takes port dependencies via constructor, exposed as `ZLayer`
- Business logic only — no encoding, no SQL

## build.mill — add a test module to core-impl if not present

```scala
object `core-impl` extends ScalaModule {
  def scalaVersion = scalaVer
  override def moduleDeps = Seq(ports)
  override def mvnDeps = zioDeps

  object test extends ScalaTests {
    def testFramework = "zio.test.sbt.ZTestFramework"
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps
    override def moduleDeps = super.moduleDeps ++ Seq(
      adapters.`in-memory-org-repository`   // add whichever in-memory adapters this service needs
    )
  }
}
```

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test for one behaviour. Inject in-memory adapters — never the real ones.

```scala
// In core/core-impl/test/src/.../GitHubOrgServiceSpec.scala
object GitHubOrgServiceSpec extends ZIOSpecDefault:
  override def spec = suite("GitHubOrgServiceSpec")(
    test("linkOrg persists the org and returns it") {
      for
        svc   <- ZIO.service[GitHubOrgService]
        org   <- svc.linkOrg(UserId("user-1"), OrgName("my-org"))
        repo  <- ZIO.service[OrgRepository]
        found <- repo.findById(org.id)
      yield assertTrue(
        found.isDefined,
        found.get.name == OrgName("my-org")
      )
    }
  ).provide(
    InMemoryOrgRepository.layer,
    GitHubOrgServiceLive.layer
  )
```

Run: `./mill <service>.core.\`core-impl\`.test`
Expected: FAIL — service not yet implemented.

**GREEN** — Write minimal implementation. Run tests again.
Expected: PASS.

**REFACTOR** — Any business logic creeping into the adapter layer? Move it here.

Repeat for each behaviour.

## Naming rules
- Service trait: `<Feature>Service` — no infra in the name
- Implementation: `<Feature>ServiceLive`
- Layer: `URLayer[PortDependencies, ServiceTrait]`

## Report back

When complete:
1. **Service traits and implementations** — name, location, methods
2. **Port dependencies used** — which ports the service depends on and which methods it calls
3. **Tests written** — one line per test: what behaviour it exercises
4. **Deviations from plan** — anything that changed
5. **Proposed amendments** — changes needed to adapter layers
````

- [ ] **Step 2: Commit**

```bash
git add .opencode/skills/feature-tdd/layers/core.md
git commit -m "feat(skills): add feature-tdd core layer template"
```

---

### Task 5: Create layers/driven-adapter.md

**Files:**
- Create: `.opencode/skills/feature-tdd/layers/driven-adapter.md`

- [ ] **Step 1: Create layers/driven-adapter.md**

````markdown
# Driven Adapter Layer — Sub-Agent Instructions

You are implementing a driven (outbound) adapter — the infrastructure implementation of a port trait. This is either a Magnum repository against PostgreSQL or a Tapir HTTP client against an external service.

## Naming rule (critical)

The class name must describe the full tech stack. `MagnumOrgRepository` and `TapirGitHubClient` are correct. `OrgRepository` and `GitHubClient` are wrong — those are port names.

---

## Magnum repository adapters

Location: `<service>/core/adapters/magnum-<name>/src/ubc/<service>/core/adapters/magnum/`

```scala
package ubc.<service>.core.adapters.magnum

import ubc.<service>.core.ports.OrgRepository
import ubc.<service>.domain.*
import ubc.<service>.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.<service>.domain.internal.adapters.magnum.PrivateMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*

private given DbCodec[GitHubOrg] = DbCodec.derived[GitHubOrg]

class MagnumOrgRepository(xa: TransactorZIO) extends OrgRepository:

  def save(org: GitHubOrg): Task[Unit] =
    xa.transact {
      sql"""
        INSERT INTO github_orgs (id, name, created_at)
        VALUES (${org.id}, ${org.name}, ${org.createdAt})
        ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
      """.update.run()
    }.unit

  def findById(id: OrgId): Task[Option[GitHubOrg]] =
    xa.connect {
      sql"SELECT id, name, created_at FROM github_orgs WHERE id = $id"
        .query[GitHubOrg].run().headOption
    }

  def delete(id: OrgId): Task[Unit] =
    xa.transact {
      sql"DELETE FROM github_orgs WHERE id = $id".update.run()
    }.unit

object MagnumOrgRepository:
  val layer: ZLayer[TransactorZIO, Nothing, OrgRepository] =
    ZLayer.fromFunction(new MagnumOrgRepository(_))
```

Notes:
- `xa.connect` for single reads (no transaction overhead)
- `xa.transact` for writes and multi-statement reads
- `SELECT` column order must match case class field declaration order — Magnum maps by position
- See `relational-database-modeling` skill for `DbCodec` wiring details

### build.mill — Magnum adapter module

```scala
object `magnum-org-repository` extends ScalaModule {
  def scalaVersion = scalaVer
  override def moduleDeps = Seq(
    ports,
    domain.domainPublicAdapterExtensions.magnum,
    domain.domainPrivateAdapterExtensions.magnum
  )
  override def mvnDeps = zioDeps ++ magnumDeps

  object test extends ScalaTests {
    def testFramework = "zio.test.sbt.ZTestFramework"
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps ++ Seq(mvn"org.slf4j:slf4j-simple:2.0.17")
    override def moduleDeps = super.moduleDeps ++ Seq(
      common.`db-test-support`,
      `db-migrations`
    )
  }
}
```

### Magnum integration tests

One Testcontainers PostgreSQL container per suite. Truncate the table before each test. Run sequentially.

```scala
// In core/adapters/magnum-org-repository/test/src/.../MagnumOrgRepositorySpec.scala
object MagnumOrgRepositorySpec extends ZIOSpecDefault:

  private val migrationLocation = "classpath:db/migration"

  private val truncateOrgs: URIO[TransactorZIO, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](
      _.transact { sql"TRUNCATE TABLE github_orgs RESTART IDENTITY".update.run() }
    ).unit.orDie

  override def spec =
    (suite("MagnumOrgRepositorySpec")(

      test("save persists an org and findById returns it") {
        for
          repo  <- ZIO.service[OrgRepository]
          org    = GitHubOrg(OrgId(0L), OrgName("test-org"), Instant.parse("2025-01-01T00:00:00Z"))
          _     <- repo.save(org)
          found <- repo.findById(OrgId(1L))  // BIGSERIAL assigns id = 1
        yield assertTrue(
          found.isDefined,
          found.get.name == OrgName("test-org")
        )
      },

      test("findById returns None for an unknown id") {
        for
          repo   <- ZIO.service[OrgRepository]
          result <- repo.findById(OrgId(99L))
        yield assertTrue(result.isEmpty)
      },

      test("save is an upsert — second save replaces the first") {
        for
          repo    <- ZIO.service[OrgRepository]
          org      = GitHubOrg(OrgId(0L), OrgName("original"), Instant.parse("2025-01-01T00:00:00Z"))
          updated  = org.copy(name = OrgName("updated"))
          _       <- repo.save(org)
          _       <- repo.save(updated)
          found   <- repo.findById(OrgId(1L))
        yield assertTrue(found.get.name == OrgName("updated"))
      },

      test("delete removes the org") {
        for
          repo     <- ZIO.service[OrgRepository]
          org       = GitHubOrg(OrgId(0L), OrgName("to-delete"), Instant.parse("2025-01-01T00:00:00Z"))
          _        <- repo.save(org)
          _        <- repo.delete(OrgId(1L))
          afterDel <- repo.findById(OrgId(1L))
        yield assertTrue(afterDel.isEmpty)
      }

    ) @@ TestAspect.before(truncateOrgs) @@ TestAspect.sequential)
      .provideShared(
        TestDatabase.suiteLayer(migrationLocation),
        TestDatabase.transactorLayer,
        MagnumOrgRepository.layer
      )
```

Run: `./mill <service>.core.adapters.\`magnum-org-repository\`.test`

---

## Tapir HTTP client adapters

Location: `<service>/core/adapters/tapir-<name>/src/ubc/<service>/core/adapters/tapir/`

```scala
class TapirGitHubClient(backend: SttpBackend[Task, Any]) extends GitHubPort:

  private val getRepoEndpoint =
    endpoint.get
      .in("repos" / path[String]("owner") / path[String]("repo"))
      .out(jsonBody[GitHubRepo])
      .errorOut(stringBody)

  def getRepo(owner: RepoOwner, name: RepoName): Task[GitHubRepo] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(getRepoEndpoint, Some(uri"https://api.github.com"))
      .apply((owner.unwrap, name.unwrap))
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(Exception(_)))

object TapirGitHubClient:
  val layer: ZLayer[SttpBackend[Task, Any], Nothing, GitHubPort] =
    ZLayer.fromFunction(new TapirGitHubClient(_))
```

### Tapir client tests — SttpBackendStub

```scala
import sttp.client3.testing.SttpBackendStub
import sttp.client3.impl.zio.RIOMonadAsyncError

object TapirGitHubClientSpec extends ZIOSpecDefault:

  private val stubBackend: ULayer[SttpBackend[Task, Any]] =
    ZLayer.succeed(
      SttpBackendStub(new RIOMonadAsyncError[Any])
        .whenRequestMatchesPartial {
          case r if r.uri.path.startsWith(List("repos")) =>
            Response.ok("""{"owner":"octocat","name":"Hello-World","description":"test"}""")
        }
    )

  override def spec = suite("TapirGitHubClientSpec")(
    test("getRepo returns a parsed repo") {
      for
        client <- ZIO.service[GitHubPort]
        repo   <- client.getRepo(RepoOwner("octocat"), RepoName("Hello-World"))
      yield assertTrue(repo.name == RepoName("Hello-World"))
    }
  ).provide(stubBackend, TapirGitHubClient.layer)
```

---

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test for one repository method or client call. Run it. Confirm it fails because the implementation does not exist.

**GREEN** — Write the minimal implementation (one SQL query or one Tapir endpoint). Run the test. Confirm it passes.

**REFACTOR** — Column order correct? SQL minimal? Naming matches the full tech stack?

Repeat for each port method.

## Report back

When complete:
1. **Adapter implemented** — class name, port it implements, each method
2. **Tests written** — one line per test: what it verifies
3. **SQL schema assumptions** — table name, column names and types, any constraints relied upon
4. **Deviations from plan** — anything that changed
5. **Proposed amendments** — changes needed to the driving adapter layer or server wiring
````

- [ ] **Step 2: Commit**

```bash
git add .opencode/skills/feature-tdd/layers/driven-adapter.md
git commit -m "feat(skills): add feature-tdd driven-adapter layer template"
```

---

### Task 6: Create layers/driving-adapter.md

**Files:**
- Create: `.opencode/skills/feature-tdd/layers/driving-adapter.md`

- [ ] **Step 1: Create layers/driving-adapter.md**

````markdown
# Driving Adapter Layer — Sub-Agent Instructions

You are implementing the driving (inbound) adapter — the HTTP controller that receives external requests and delegates to the core service. No business logic lives here.

## What to build

Location: `<service>/api/internal-api-adapters/http/src/ubc/<service>/api/internal/http/`

```scala
package ubc.<service>.api.internal.http

import ubc.<service>.core.<Feature>Service
import ubc.<service>.domain.*
import ubc.<service>.domain.adapters.json.PublicJsonCodecs.given
import ubc.common.TapirTracingInterceptor
import sttp.tapir.ztapir.*
import sttp.tapir.server.ziohttp.*
import sttp.tapir.json.zio.*
import zio.*
import zio.http.{Response, Routes}
import zio.telemetry.opentelemetry.tracing.Tracing

// Inbound HTTP adapter. Decodes requests, delegates to core. No business logic.
object <Feature>HttpController:

  val doThingEndpoint =
    endpoint.post
      .in("path" / "to" / "resource")
      .in(jsonBody[RequestType])
      .out(jsonBody[ResponseType])
      .errorOut(stringBody)

  def routes(service: <Feature>Service, tracing: Tracing): Routes[Any, Response] =
    val interpreter = ZioHttpInterpreter(TapirTracingInterceptor.serverOptions(tracing))
    val doThing = doThingEndpoint.zServerLogic[Any] { input =>
      service.doThing(input).mapError(_.getMessage)
    }
    interpreter.toHttp(doThing)

  val layer: ZLayer[<Feature>Service & Tracing, Nothing, Routes[Any, Response]] =
    ZLayer.fromFunction(routes)
```

Rules:
- Decode request → call service → encode response. Nothing else.
- Endpoint shapes must be defined here to match `api/api-defn` — never invent new HTTP contracts
- Depends on `api/api-defn` and `core/core-impl`. Never depends on port traits or adapter modules directly.

## build.mill — add test module to internal-api-adapters/http if not present

```scala
object http extends ScalaModule {
  def scalaVersion = scalaVer
  override def moduleDeps = Seq(
    `api-defn`.jvm,
    core.`core-impl`,
    domain.domainPublicAdapterExtensions.`zio-json`.jvm,
    common.`tapir-tracing-interceptor`
  )
  override def mvnDeps = zioDeps ++ tapirServerDeps ++ neotypeTapirDeps

  object test extends ScalaTests {
    def testFramework = "zio.test.sbt.ZTestFramework"
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps ++ tapirClientDeps
    override def moduleDeps = super.moduleDeps ++ Seq(
      core.adapters.`in-memory-<name>`   // inject in-memory core via this
    )
  }
}
```

## Test infrastructure — Tapir stub interpreter

Test HTTP routes without a running server. Inject an in-memory core service.

```scala
import sttp.client3.{basicRequest, UriContext}
import sttp.client3.testing.SttpBackendStub
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.*

object <Feature>HttpControllerSpec extends ZIOSpecDefault:
  override def spec = suite("<Feature>HttpControllerSpec")(
    test("POST /path/to/resource returns 200 with expected response") {
      for
        svc <- ZIO.service[<Feature>Service]
        backend = TapirStubInterpreter(SttpBackendStub(new RIOMonadAsyncError[Any]))
          .whenServerEndpointRunLogic(
            <Feature>HttpController.doThingEndpoint.zServerLogic[Any] { input =>
              svc.doThing(input).mapError(_.getMessage)
            }
          )
          .backend()
        resp <- basicRequest
          .post(uri"http://test/path/to/resource")
          .body("""{"field":"value"}""")
          .send(backend)
      yield assertTrue(resp.code.isSuccess)
    }
  ).provide(InMemory<Feature>Service.layer)
```

Run: `./mill <service>.api.\`internal-api-adapters\`.http.test`

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test for one endpoint. Run it. Confirm it fails because the route does not exist.

**GREEN** — Implement the minimal route handler. Run the test.
Expected: PASS.

**REFACTOR** — Is there any business logic in the controller? Move it to core. Stay green.

Repeat for each endpoint.

## Naming rules
- Controller name includes the protocol: `<Feature>HttpController` not `<Feature>Controller`
- Module dir: `http` for Tapir/ZIO HTTP controllers

## Report back

When complete:
1. **Endpoints implemented** — HTTP method, path, request type, response type
2. **Tests written** — one line per test: what it verifies
3. **Deviations from plan** — anything that changed
4. **Server wiring needed** — which modules `server` must add to `moduleDeps` to wire this feature end-to-end
````

- [ ] **Step 2: Commit**

```bash
git add .opencode/skills/feature-tdd/layers/driving-adapter.md
git commit -m "feat(skills): add feature-tdd driving-adapter layer template"
```

---

### Task 7: Verify the full skill structure

**Files:** No new files — verification only.

- [ ] **Step 1: Confirm all files exist**

```bash
find .opencode/skills/feature-tdd -type f | sort
```

Expected output:
```
.opencode/skills/feature-tdd/SKILL.md
.opencode/skills/feature-tdd/layers/domain.md
.opencode/skills/feature-tdd/layers/driven-adapter.md
.opencode/skills/feature-tdd/layers/driving-adapter.md
.opencode/skills/feature-tdd/layers/core.md
.opencode/skills/feature-tdd/layers/ports.md
```

- [ ] **Step 2: Confirm SKILL.md frontmatter is valid**

```bash
head -5 .opencode/skills/feature-tdd/SKILL.md
```

Expected: `---`, `name: feature-tdd`, `description: Use when...`

- [ ] **Step 3: Commit the plan file alongside the skill**

```bash
git add docs/superpowers/plans/2026-04-24-feature-tdd-skill.md
git commit -m "docs: add feature-tdd skill implementation plan"
```
