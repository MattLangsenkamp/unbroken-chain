package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object InMemoryPendingLinkFlowRepositorySpec extends ZIOSpecDefault:

  // LinkState is a UUID; map a readable label to a stable UUID so test keys stay legible.
  private def ls(label: String): LinkState = LinkState(UUID.nameUUIDFromBytes(label.getBytes))

  private def flow(state: String, expiresAt: Instant): PendingLinkFlow =
    PendingLinkFlow(
      state     = ls(state),
      createdAt = Instant.parse("2026-04-25T12:00:00Z"),
      expiresAt = expiresAt
    )

  override def spec =
    suite("InMemoryPendingLinkFlowRepository")(
      test("insert + findByState round-trip") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          f    = flow("nonce-1", Instant.parse("2026-04-25T12:10:00Z"))
          _    <- repo.insert(f)
          got  <- repo.findByState(ls("nonce-1"))
        yield assertTrue(got.contains(f))
      },
      test("findByState returns None for unknown state") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          got  <- repo.findByState(ls("does-not-exist"))
        yield assertTrue(got.isEmpty)
      },
      test("deleteByState returns true when present, false when absent") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          f    = flow("to-delete", Instant.parse("2026-04-25T12:10:00Z"))
          _    <- repo.insert(f)
          first <- repo.deleteByState(ls("to-delete"))
          again <- repo.deleteByState(ls("to-delete"))
        yield assertTrue(first, !again)
      },
      test("deleteExpired removes only rows whose expiresAt <= now") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          past   = flow("past", Instant.parse("2026-04-25T11:00:00Z"))
          edge   = flow("edge", Instant.parse("2026-04-25T12:00:00Z"))
          future = flow("future", Instant.parse("2026-04-25T13:00:00Z"))
          _   <- repo.insert(past)
          _   <- repo.insert(edge)
          _   <- repo.insert(future)
          n   <- repo.deleteExpired(Instant.parse("2026-04-25T12:00:00Z"))
          ps  <- repo.findByState(ls("past"))
          es  <- repo.findByState(ls("edge"))
          fs  <- repo.findByState(ls("future"))
        yield assertTrue(
          n == 2,
          ps.isEmpty,
          es.isEmpty,
          fs.contains(future)
        )
      }
    ).provide(InMemoryPendingLinkFlowRepository.layer)
