package ubc.githubgateway.core.adapters.magnum

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import ubc.common.TestDatabase
import ubc.common.pagination.PageRequest
import ubc.githubgateway.core.ports.LinkedRepoRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import neotype.*
import zio.*
import zio.test.*

import java.time.Instant

/** Integration tests for [[MagnumLinkedRepoRepository]] against a real PostgreSQL.
  *
  * Each test seeds an `installation` row directly via `TransactorZIO` so we don't need to
  * pull the installation repo into the build graph just to satisfy the FK.
  */
object MagnumLinkedRepoRepositorySpec extends ZIOSpecDefault:

  private val migrationLocation = "classpath:db/migration"

  private val truncate: URIO[TransactorZIO, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](
      _.transact {
        sql"TRUNCATE TABLE installation, linked_repo RESTART IDENTITY CASCADE".update.run()
      }
    ).unit.orDie

  // Seed an installation row and return its assigned local id.
  private def seedInstallation(ghId: Long): URIO[TransactorZIO, InstallationId] =
    ZIO.serviceWithZIO[TransactorZIO](_.transact {
      sql"""INSERT INTO installation
              (gh_installation_id, account_login, account_id, account_type, status, installed_at)
            VALUES ($ghId, ${"someone"}, ${10L}, ${"User"}, ${"Active"}, ${Instant.parse("2025-01-01T00:00:00Z")})
            RETURNING id""".returning[InstallationId].run().head
    }).orDie

  override def spec =
    (suite("MagnumLinkedRepoRepositorySpec")(

      test("insert returns row with assigned id") {
        for
          repo   <- ZIO.service[LinkedRepoRepository]
          instId <- seedInstallation(2001L)
          row    <- repo.insert(instId, GhRepositoryId(100L), RepoFullName("octocat/hello-world"))
        yield assertTrue(
          row.installationId == instId,
          row.ghRepositoryId == GhRepositoryId(100L),
          row.fullName       == RepoFullName("octocat/hello-world"),
          row.id.unwrap      > 0L
        )
      },

      test("replaceSet from empty inserts everything; summary added=N, removed=0, renamed=0") {
        for
          repo    <- ZIO.service[LinkedRepoRepository]
          instId  <- seedInstallation(2001L)
          summary <- repo.replaceSet(instId, List(
                       (GhRepositoryId(100L), RepoFullName("octocat/hello-world")),
                       (GhRepositoryId(101L), RepoFullName("octocat/spoon-knife"))
                     ))
          page    <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(
          summary.added   == 2,
          summary.removed == 0,
          summary.renamed == 0,
          page.items.size == 2,
          page.total      == 2L
        )
      },

      test("replaceSet with overlap counts adds, removes, and renames") {
        for
          repo   <- ZIO.service[LinkedRepoRepository]
          instId <- seedInstallation(2001L)
          // Initial: {100→hello-world, 101→spoon-knife}
          _      <- repo.replaceSet(instId, List(
                      (GhRepositoryId(100L), RepoFullName("octocat/hello-world")),
                      (GhRepositoryId(101L), RepoFullName("octocat/spoon-knife"))
                    ))
          // Desired: {100→hello-world (unchanged), 101→spoon-knife-renamed (rename), 102→new (add)}; 101 stays but renamed; original 101 is renamed not removed.
          summary <- repo.replaceSet(instId, List(
                       (GhRepositoryId(100L), RepoFullName("octocat/hello-world")),
                       (GhRepositoryId(101L), RepoFullName("octocat/spoon-knife-renamed")),
                       (GhRepositoryId(102L), RepoFullName("octocat/cli"))
                     ))
          page    <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(
          summary.added   == 1, // 102
          summary.removed == 0,
          summary.renamed == 1, // 101
          page.total      == 3L
        )
      },

      test("replaceSet rename: matching ghRepositoryId, different fullName -> renamed=1") {
        for
          repo    <- ZIO.service[LinkedRepoRepository]
          instId  <- seedInstallation(2001L)
          _       <- repo.insert(instId, GhRepositoryId(100L), RepoFullName("old/name"))
          summary <- repo.replaceSet(instId, List(
                       (GhRepositoryId(100L), RepoFullName("new/name"))
                     ))
          page    <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(
          summary.added   == 0,
          summary.removed == 0,
          summary.renamed == 1,
          page.items.head.fullName == RepoFullName("new/name")
        )
      },

      test("replaceSet removes rows missing from desired") {
        for
          repo    <- ZIO.service[LinkedRepoRepository]
          instId  <- seedInstallation(2001L)
          _       <- repo.insert(instId, GhRepositoryId(100L), RepoFullName("a/a"))
          _       <- repo.insert(instId, GhRepositoryId(101L), RepoFullName("b/b"))
          summary <- repo.replaceSet(instId, List((GhRepositoryId(100L), RepoFullName("a/a"))))
          page    <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(
          summary.added   == 0,
          summary.removed == 1,
          summary.renamed == 0,
          page.total      == 1L,
          page.items.head.ghRepositoryId == GhRepositoryId(100L)
        )
      },

      test("listByInstallation paginates and only returns rows for that installation") {
        for
          repo   <- ZIO.service[LinkedRepoRepository]
          inst1  <- seedInstallation(2001L)
          inst2  <- seedInstallation(2002L)
          _      <- repo.insert(inst1, GhRepositoryId(100L), RepoFullName("inst1/r1"))
          _      <- repo.insert(inst1, GhRepositoryId(101L), RepoFullName("inst1/r2"))
          _      <- repo.insert(inst2, GhRepositoryId(200L), RepoFullName("inst2/r1"))
          page1  <- repo.listByInstallation(inst1, PageRequest(None, 1))
          page2  <- repo.listByInstallation(inst1, PageRequest(page1.nextCursor, 10))
        yield assertTrue(
          page1.items.size == 1,
          page1.total      == 2L,
          page1.items.head.ghRepositoryId == GhRepositoryId(100L),
          page2.items.size == 1,
          page2.total      == 2L,
          page2.items.head.ghRepositoryId == GhRepositoryId(101L),
          page2.nextCursor.isEmpty,
          // Ensure no inst2 row leaked into either page
          (page1.items ++ page2.items).forall(_.installationId == inst1)
        )
      },

      test("listAll paginates across installations") {
        for
          repo  <- ZIO.service[LinkedRepoRepository]
          inst1 <- seedInstallation(2001L)
          inst2 <- seedInstallation(2002L)
          _     <- repo.insert(inst1, GhRepositoryId(100L), RepoFullName("inst1/r1"))
          _     <- repo.insert(inst2, GhRepositoryId(200L), RepoFullName("inst2/r1"))
          page1 <- repo.listAll(PageRequest(None, 1))
          page2 <- repo.listAll(PageRequest(page1.nextCursor, 10))
        yield assertTrue(
          page1.items.size == 1,
          page1.total      == 2L,
          page2.items.size == 1,
          page2.nextCursor.isEmpty
        )
      },

      test("renameByGhRepositoryId updates full_name in place") {
        for
          repo   <- ZIO.service[LinkedRepoRepository]
          instId <- seedInstallation(2001L)
          _      <- repo.insert(instId, GhRepositoryId(100L), RepoFullName("old/name"))
          _      <- repo.renameByGhRepositoryId(GhRepositoryId(100L), RepoFullName("new/name"))
          page   <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(page.items.head.fullName == RepoFullName("new/name"))
      },

      test("renameByGhRepositoryId is a no-op when no row matches") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          _    <- repo.renameByGhRepositoryId(GhRepositoryId(99999L), RepoFullName("new/name"))
        yield assertTrue(true)
      },

      test("insertMany inserts all rows for one installation") {
        for
          repo   <- ZIO.service[LinkedRepoRepository]
          instId <- seedInstallation(2001L)
          _      <- repo.insertMany(instId, List(
                      (GhRepositoryId(100L), RepoFullName("a/a")),
                      (GhRepositoryId(101L), RepoFullName("b/b")),
                      (GhRepositoryId(102L), RepoFullName("c/c"))
                    ))
          page   <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(page.items.size == 3, page.total == 3L)
      },

      test("insertMany with empty list is a no-op") {
        for
          repo   <- ZIO.service[LinkedRepoRepository]
          instId <- seedInstallation(2001L)
          _      <- repo.insertMany(instId, Nil)
          page   <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(page.total == 0L)
      },

      test("deleteByGhRepositoryIds removes only the named ids in that installation") {
        for
          repo  <- ZIO.service[LinkedRepoRepository]
          inst1 <- seedInstallation(2001L)
          inst2 <- seedInstallation(2002L)
          _     <- repo.insert(inst1, GhRepositoryId(100L), RepoFullName("a/a"))
          _     <- repo.insert(inst1, GhRepositoryId(101L), RepoFullName("b/b"))
          _     <- repo.insert(inst1, GhRepositoryId(102L), RepoFullName("c/c"))
          _     <- repo.insert(inst2, GhRepositoryId(100L), RepoFullName("inst2/a"))
          _     <- repo.deleteByGhRepositoryIds(inst1, List(GhRepositoryId(100L), GhRepositoryId(101L)))
          page1 <- repo.listByInstallation(inst1, PageRequest(None, 10))
          page2 <- repo.listByInstallation(inst2, PageRequest(None, 10))
        yield assertTrue(
          page1.items.size == 1,
          page1.items.head.ghRepositoryId == GhRepositoryId(102L),
          page2.items.size == 1, // unchanged — different installation
          page2.items.head.ghRepositoryId == GhRepositoryId(100L)
        )
      },

      test("deleteByGhRepositoryIds with empty list is a no-op") {
        for
          repo   <- ZIO.service[LinkedRepoRepository]
          instId <- seedInstallation(2001L)
          _      <- repo.insert(instId, GhRepositoryId(100L), RepoFullName("a/a"))
          _      <- repo.deleteByGhRepositoryIds(instId, Nil)
          page   <- repo.listByInstallation(instId, PageRequest(None, 10))
        yield assertTrue(page.total == 1L)
      }

    ) @@ TestAspect.before(truncate) @@ TestAspect.sequential)
      .provideShared(
        TestDatabase.suiteLayer(migrationLocation),
        TestDatabase.transactorLayer,
        MagnumLinkedRepoRepository.layer
      )
