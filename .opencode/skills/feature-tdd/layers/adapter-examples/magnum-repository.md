# Magnum PostgreSQL Repository — Reference

Use this when your driven adapter is a Magnum repository against PostgreSQL.

## Location

`<service>/core/adapters/magnum-<name>/src/ubc/<service>/core/adapters/magnum/`

## Implementation

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
- Omit `id` from INSERT columns when the table uses `BIGSERIAL` — let the DB assign it
- See `relational-database-modeling` skill for `DbCodec` wiring detail

## build.mill

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

## Integration tests

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
