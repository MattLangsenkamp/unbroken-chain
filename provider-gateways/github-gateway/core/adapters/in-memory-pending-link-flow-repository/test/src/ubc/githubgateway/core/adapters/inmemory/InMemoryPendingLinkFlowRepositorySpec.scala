package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant

object InMemoryPendingLinkFlowRepositorySpec extends ZIOSpecDefault:

  private def flow(state: String, expiresAt: Instant): PendingLinkFlow =
    PendingLinkFlow(
      state             = LinkState(state),
      encryptedVerifier = EncryptedBytes(s"enc-$state"),
      codeChallenge     = CodeChallenge(s"challenge-$state"),
      createdAt         = Instant.parse("2026-04-25T12:00:00Z"),
      expiresAt         = expiresAt
    )

  override def spec =
    suite("InMemoryPendingLinkFlowRepository")(
      test("insert + findByState round-trip") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          f    = flow("nonce-1", Instant.parse("2026-04-25T12:10:00Z"))
          _    <- repo.insert(f)
          got  <- repo.findByState(LinkState("nonce-1"))
        yield assertTrue(got.contains(f))
      },
      test("findByState returns None for unknown state") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          got  <- repo.findByState(LinkState("does-not-exist"))
        yield assertTrue(got.isEmpty)
      },
      test("deleteByState returns true when present, false when absent") {
        for
          repo <- ZIO.service[PendingLinkFlowRepository]
          f    = flow("to-delete", Instant.parse("2026-04-25T12:10:00Z"))
          _    <- repo.insert(f)
          first <- repo.deleteByState(LinkState("to-delete"))
          again <- repo.deleteByState(LinkState("to-delete"))
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
          ps  <- repo.findByState(LinkState("past"))
          es  <- repo.findByState(LinkState("edge"))
          fs  <- repo.findByState(LinkState("future"))
        yield assertTrue(
          n == 2,
          ps.isEmpty,
          es.isEmpty,
          fs.contains(future)
        )
      }
    ).provide(InMemoryPendingLinkFlowRepository.layer)
