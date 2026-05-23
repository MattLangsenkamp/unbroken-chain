package ubc.githubgateway.core.adapters.magnum

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import ubc.common.TestDatabase
import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.LinkState
import ubc.githubgateway.domain.internal.PendingLinkFlow
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** Integration tests for [[MagnumPendingLinkFlowRepository]] against a real PostgreSQL. */
object MagnumPendingLinkFlowRepositorySpec extends ZIOSpecDefault:

  private val migrationLocation = "classpath:db/migration"

  // LinkState is a UUID; map a readable label to a stable UUID so test keys stay legible.
  private def ls(label: String): LinkState = LinkState(UUID.nameUUIDFromBytes(label.getBytes))

  private val truncate: URIO[TransactorZIO, Unit] =
    ZIO
      .serviceWithZIO[TransactorZIO](
        _.transact { sql"TRUNCATE TABLE pending_link_flow".update.run() }
      )
      .unit
      .orDie

  private def flow(
      state: String,
      expiresAt: Instant = Instant.parse("2025-01-01T00:10:00Z")
  ): PendingLinkFlow =
    PendingLinkFlow(
      state = ls(state),
      createdAt = Instant.parse("2025-01-01T00:00:00Z"),
      expiresAt = expiresAt
    )

  override def spec =
    (suite("MagnumPendingLinkFlowRepositorySpec")(
      test("insert + findByState round-trip preserves all fields") {
        val original = flow("state-1")
        for
          repo  <- ZIO.service[PendingLinkFlowRepository]
          _     <- repo.insert(original)
          found <- repo.findByState(ls("state-1"))
        yield assertTrue(
          found.isDefined,
          found.get.state == original.state,
          found.get.createdAt == original.createdAt,
          found.get.expiresAt == original.expiresAt
        )
      },

      test("findByState returns None for an unknown state") {
        for
          repo   <- ZIO.service[PendingLinkFlowRepository]
          result <- repo.findByState(ls("nope"))
        yield assertTrue(result.isEmpty)
      },

      test("deleteByState returns true on hit and the row is gone") {
        for
          repo    <- ZIO.service[PendingLinkFlowRepository]
          _       <- repo.insert(flow("state-1"))
          deleted <- repo.deleteByState(ls("state-1"))
          after   <- repo.findByState(ls("state-1"))
        yield assertTrue(deleted, after.isEmpty)
      },

      test("deleteByState returns false on miss") {
        for
          repo    <- ZIO.service[PendingLinkFlowRepository]
          deleted <- repo.deleteByState(ls("nothing-here"))
        yield assertTrue(!deleted)
      },

      test("deleteExpired is INCLUSIVE of rows whose expires_at == now") {
        val cutoff = Instant.parse("2025-01-01T00:10:00Z")
        for
          repo   <- ZIO.service[PendingLinkFlowRepository]
          _      <- repo.insert(flow("at-cutoff", expiresAt = cutoff))
          _      <- repo.insert(flow("future", expiresAt = cutoff.plusSeconds(60)))
          n      <- repo.deleteExpired(cutoff)
          atRow  <- repo.findByState(ls("at-cutoff"))
          future <- repo.findByState(ls("future"))
        yield assertTrue(
          n == 1, // only the at-cutoff row was swept
          atRow.isEmpty,
          future.isDefined
        )
      },

      test("deleteExpired returns 0 when no rows are expired") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          _    <- repo.insert(flow("future-1", expiresAt = Instant.parse("2099-01-01T00:00:00Z")))
          _    <- repo.insert(flow("future-2", expiresAt = Instant.parse("2099-01-02T00:00:00Z")))
          n    <- repo.deleteExpired(Instant.parse("2025-01-01T00:00:00Z"))
        yield assertTrue(n == 0)
      },

      test("deleteExpired returns N when N rows are expired") {
        val cutoff = Instant.parse("2025-06-01T00:00:00Z")
        for
          repo      <- ZIO.service[PendingLinkFlowRepository]
          _         <- repo.insert(flow("e1", expiresAt = Instant.parse("2025-01-01T00:00:00Z")))
          _         <- repo.insert(flow("e2", expiresAt = Instant.parse("2025-02-01T00:00:00Z")))
          _         <- repo.insert(flow("e3", expiresAt = Instant.parse("2025-03-01T00:00:00Z")))
          _         <- repo.insert(flow("future", expiresAt = Instant.parse("2099-01-01T00:00:00Z")))
          n         <- repo.deleteExpired(cutoff)
          futureRow <- repo.findByState(ls("future"))
        yield assertTrue(n == 3, futureRow.isDefined)
      }
    ) @@ TestAspect.before(truncate) @@ TestAspect.sequential)
      .provideShared(
        TestDatabase.suiteLayer(migrationLocation),
        TestDatabase.transactorLayer,
        MagnumPendingLinkFlowRepository.layer
      )
