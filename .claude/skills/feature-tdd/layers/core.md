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

case class GitHubOrgService(orgRepo: OrgRepository):
  def linkOrg(userId: UserId, orgName: OrgName): Task[GitHubOrg] =
    // business logic only — no SQL, no HTTP
    ???

object GitHubOrgService:
  val layer: URLayer[OrgRepository, GitHubOrgService] =
    ZLayer.fromFunction(GitHubOrgService.apply)
```

Rules:
- Depends only on `core/ports` — never on Magnum, Tapir, or any adapter
- Takes port dependencies via constructor, exposed as `ZLayer`
- Business logic only — no encoding, no SQL

## Activity logging — every method starts with one

Every public method on the service starts with a `ZIO.logActivity` call emitting a typed event from `common/activity-logging`. This gives observability into business-logic execution: structured, searchable, and impossible to misformat at the call site. Free-form `ZIO.logInfo("doing thing")` is **not** acceptable in core service code.

The contract from `common/activity-logging`:

```scala
// trait hierarchy — pick the one matching the log level you want
trait TraceLog extends ActivityLog
trait DebugLog extends ActivityLog
trait InfoLog  extends ActivityLog
trait WarnLog  extends ActivityLog
trait ErrorLog extends ActivityLog
trait FatalLog extends ActivityLog

// extension
ZIO.logActivity[A <: ActivityLog: JsonCodec](a: A): UIO[Unit]
```

Define one event case class per service method (and one per notable branch within a method, if the branch is interesting). They live next to the service, in `core-impl`:

```scala
package ubc.<service>.core

import ubc.common.{InfoLog, WarnLog, ZIO as _, *}
import zio.*
import zio.json.*

// One activity event per service method — fields capture the business inputs.
final case class LinkOrgRequested(userId: UserId, orgName: OrgName) extends InfoLog derives JsonCodec
final case class LinkOrgRejected(userId: UserId, reason: String)   extends WarnLog derives JsonCodec
```

Every method opens with the activity log:

```scala
case class GitHubOrgService(orgRepo: OrgRepository):
  def linkOrg(userId: UserId, orgName: OrgName): IO[DomainError, GitHubOrg] =
    ZIO.logActivity(LinkOrgRequested(userId, orgName)) *>
      // business logic
      orgRepo.save(...).orElseFail(DomainError.AlreadyLinked)
```

Rules:
- Each method's first effect is `ZIO.logActivity(<Event>)`. The only exceptions are methods we expect to fire at very high volume (tight inner loops, per-event hot paths) where activity emission would flood the telemetry pipeline. Skipping the log in those cases requires a comment in the method body explaining the volume concern, e.g. `// no activity log: hot path, called on every webhook line`.
- Event class names read as past-tense business facts: `LinkOrgRequested`, `WebhookSignatureRejected`, `InstallationReconciled`. Not `LinkOrgEvent` or `LogLinkOrg`.
- Pick the log level by what the event represents. Successful business operations are `InfoLog`. Domain-level rejections (validation, expired states) are `WarnLog`. Genuinely unexpected failures are `ErrorLog` — but those usually surface as ZIO defects rather than hand-rolled events.
- Add fields the event needs to be useful in a log search: identifiers, the input that triggered it, the outcome. Do NOT include secrets — the event JSON may go to durable log storage.
- The module needs `common.\`activity-logging\`` in `moduleDeps` and `zioJsonDeps` in `mvnDeps`.

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
    GitHubOrgService.layer
  )
```

Run: `./mill <service>.core.\`core-impl\`.test`
Expected: FAIL — service not yet implemented.

**GREEN** — Write minimal implementation. Run tests again.
Expected: PASS.

**REFACTOR** — Any business logic creeping into the adapter layer? Move it here.

Repeat for each behaviour.

## Naming rules
- Service: `<Feature>Service` — plain `case class`, no infra in the name
- Layer: `URLayer[PortDependencies, <Feature>Service]`

## Report back

When complete:
1. **Service traits and implementations** — name, location, methods
2. **Port dependencies used** — which ports the service depends on and which methods it calls
3. **Tests written** — one line per test: what behaviour it exercises
4. **Deviations from plan** — anything that changed
5. **Proposed amendments** — changes needed to adapter layers
