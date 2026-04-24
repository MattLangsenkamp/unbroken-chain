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
        INSERT INTO github_orgs (name, created_at)
        VALUES (${org.name}, ${org.createdAt})
        ON CONFLICT (name) DO UPDATE SET created_at = EXCLUDED.created_at
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
- See `relational-database-modeling` skill for `DbCodec` wiring detail

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
          // id = 0L is a placeholder — BIGSERIAL assigns the real id (1 for first insert)
          org    = GitHubOrg(OrgId(0L), OrgName("test-org"), Instant.parse("2025-01-01T00:00:00Z"))
          _     <- repo.save(org)
          found <- repo.findById(OrgId(1L))
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

      test("save is an upsert — re-saving same name updates the row") {
        for
          repo    <- ZIO.service[OrgRepository]
          org      = GitHubOrg(OrgId(0L), OrgName("my-org"), Instant.parse("2025-01-01T00:00:00Z"))
          updated  = org.copy(createdAt = Instant.parse("2026-01-01T00:00:00Z"))
          _       <- repo.save(org)
          _       <- repo.save(updated)       // same name → ON CONFLICT updates
          found   <- repo.findById(OrgId(1L))
        yield assertTrue(found.get.createdAt == Instant.parse("2026-01-01T00:00:00Z"))
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
