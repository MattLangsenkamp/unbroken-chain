package ubc.githubgateway.core.adapters.magnum

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import ubc.common.TestDatabase
import ubc.common.pagination.PageRequest
import ubc.githubgateway.core.ports.InstallationRepository
import ubc.githubgateway.domain.*
import neotype.*
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** Integration tests for [[MagnumInstallationRepository]] against a real PostgreSQL instance.
  *
  * Isolation strategy: one Testcontainers container + one HikariCP pool per suite;
  * the `installation` table is TRUNCATEd before each test so each test starts with an
  * empty table. Tests run sequentially.
  *
  * Note: the cascade test exercises raw SQL (the linked-repo repo lives in a sibling module
  * we deliberately do not depend on) — we INSERT into linked_repo directly to confirm the
  * FK ON DELETE CASCADE behaves as documented in the port trait.
  */
object MagnumInstallationRepositorySpec extends ZIOSpecDefault:

  private val migrationLocation = "classpath:db/migration"

  // Wipe between tests. CASCADE so linked_repo rows go too.
  private val truncate: URIO[TransactorZIO, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](
      _.transact {
        sql"TRUNCATE TABLE installation, linked_repo RESTART IDENTITY CASCADE".update.run()
      }
    ).unit.orDie

  private val ghId1   = GhInstallationId(1001L)
  private val ghId2   = GhInstallationId(1002L)
  private val login1  = AccountLogin("octocat")
  private val login2  = AccountLogin("acme")
  private val accId1  = AccountId(42L)
  private val accId2  = AccountId(43L)
  private val t0      = Instant.parse("2025-01-01T00:00:00Z")

  override def spec =
    (suite("MagnumInstallationRepositorySpec")(

      test("upsertByGhInstallationId inserts a new row with assigned id") {
        for
          repo <- ZIO.service[InstallationRepository]
          ins  <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
        yield assertTrue(
          ins.ghInstallationId == ghId1,
          ins.accountLogin     == login1,
          ins.accountId        == accId1,
          ins.accountType      == AccountType.User,
          ins.status           == InstallationStatus.Active,
          ins.installedAt      == t0,
          ins.id              != UnpersistedInstallationId
        )
      },

      test("upsertByGhInstallationId updates an existing row keyed by ghInstallationId — id preserved") {
        for
          repo    <- ZIO.service[InstallationRepository]
          first   <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
          updated <- repo.upsertByGhInstallationId(
                       ghId1,
                       AccountLogin("octocat-renamed"),
                       accId1,
                       AccountType.Organization,
                       InstallationStatus.Suspended,
                       t0.plusSeconds(60)
                     )
        yield assertTrue(
          updated.id               == first.id, // surrogate id preserved
          updated.ghInstallationId == ghId1,
          updated.accountLogin     == AccountLogin("octocat-renamed"),
          updated.accountType      == AccountType.Organization,
          updated.status           == InstallationStatus.Suspended,
          updated.installedAt      == t0.plusSeconds(60)
        )
      },

      test("findByGhInstallationId returns Some when row exists") {
        for
          repo  <- ZIO.service[InstallationRepository]
          ins   <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
          found <- repo.findByGhInstallationId(ghId1)
        yield assertTrue(
          found.isDefined,
          found.get == ins
        )
      },

      test("findByGhInstallationId returns None when no row exists") {
        for
          repo   <- ZIO.service[InstallationRepository]
          result <- repo.findByGhInstallationId(GhInstallationId(9999L))
        yield assertTrue(result.isEmpty)
      },

      test("listAll paginates and returns total count + nextCursor when more remain") {
        for
          repo  <- ZIO.service[InstallationRepository]
          _     <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
          _     <- repo.upsertByGhInstallationId(ghId2, login2, accId2, AccountType.Organization, InstallationStatus.Active, t0)
          page1 <- repo.listAll(PageRequest(cursor = None, limit = 1))
          page2 <- repo.listAll(PageRequest(cursor = page1.nextCursor, limit = 10))
        yield assertTrue(
          page1.items.size      == 1,
          page1.total           == 2L,
          page1.nextCursor.isDefined,
          page2.items.size      == 1,
          page2.total           == 2L,
          page2.nextCursor.isEmpty,
          // UUID PKs: ORDER BY id is stable but not insertion order, so assert the pages
          // partition both installations across the two pages.
          Set(page1.items.head.ghInstallationId, page2.items.head.ghInstallationId) == Set(ghId1, ghId2)
        )
      },

      test("listAll respects limit") {
        for
          repo <- ZIO.service[InstallationRepository]
          _    <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
          _    <- repo.upsertByGhInstallationId(ghId2, login2, accId2, AccountType.Organization, InstallationStatus.Active, t0)
          page <- repo.listAll(PageRequest(cursor = None, limit = 1))
        yield assertTrue(page.items.size == 1, page.total == 2L)
      },

      test("deleteByGhInstallationId returns true on hit") {
        for
          repo    <- ZIO.service[InstallationRepository]
          _       <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
          deleted <- repo.deleteByGhInstallationId(ghId1)
          after   <- repo.findByGhInstallationId(ghId1)
        yield assertTrue(deleted, after.isEmpty)
      },

      test("deleteByGhInstallationId returns false on miss") {
        for
          repo    <- ZIO.service[InstallationRepository]
          deleted <- repo.deleteByGhInstallationId(GhInstallationId(424242L))
        yield assertTrue(!deleted)
      },

      test("updateStatus toggles the status field") {
        for
          repo  <- ZIO.service[InstallationRepository]
          _     <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
          _     <- repo.updateStatus(ghId1, InstallationStatus.Suspended)
          afterSuspend <- repo.findByGhInstallationId(ghId1)
          _     <- repo.updateStatus(ghId1, InstallationStatus.Active)
          afterReactivate <- repo.findByGhInstallationId(ghId1)
        yield assertTrue(
          afterSuspend.get.status    == InstallationStatus.Suspended,
          afterReactivate.get.status == InstallationStatus.Active
        )
      },

      test("updateStatus is a no-op for an unknown ghInstallationId") {
        for
          repo <- ZIO.service[InstallationRepository]
          _    <- repo.updateStatus(GhInstallationId(99999L), InstallationStatus.Suspended)
        yield assertTrue(true)
      },

      test("deleting an installation cascades linked_repo rows") {
        for
          repo <- ZIO.service[InstallationRepository]
          ins  <- repo.upsertByGhInstallationId(ghId1, login1, accId1, AccountType.User, InstallationStatus.Active, t0)
          // Insert a linked_repo row directly via TransactorZIO — we don't depend on the linked-repo repo here.
          _ <- ZIO.serviceWithZIO[TransactorZIO](_.transact {
                 val instId = ins.id.unwrap        // UUID — Magnum's built-in DbCodec binds it
                 val rid    = UUID.randomUUID()     // linked_repo.id is app-generated now
                 sql"""INSERT INTO linked_repo (id, installation_id, gh_repository_id, full_name)
                       VALUES ($rid, $instId, ${100L}, ${"octocat/hello-world"})""".update.run()
               })
          beforeCount <- ZIO.serviceWithZIO[TransactorZIO](_.connect {
                           sql"SELECT COUNT(*) FROM linked_repo".query[Long].run().head
                         })
          _           <- repo.deleteByGhInstallationId(ghId1)
          afterCount  <- ZIO.serviceWithZIO[TransactorZIO](_.connect {
                           sql"SELECT COUNT(*) FROM linked_repo".query[Long].run().head
                         })
        yield assertTrue(beforeCount == 1L, afterCount == 0L)
      }

    ) @@ TestAspect.before(truncate) @@ TestAspect.sequential)
      .provideShared(
        TestDatabase.suiteLayer(migrationLocation),
        TestDatabase.transactorLayer,
        MagnumInstallationRepository.layer
      )
