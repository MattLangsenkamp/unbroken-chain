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
  ).provide(InMemoryOrgRepository.layer) @@ TestAspect.sequential
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
