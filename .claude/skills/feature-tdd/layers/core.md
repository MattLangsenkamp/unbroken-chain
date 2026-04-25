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
