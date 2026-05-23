package ubc.githubgateway.core

import ubc.common.securerandom.inmemory.DeterministicSecureRandom
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object GitHubGatewayServiceSweepSpec extends ZIOSpecDefault:

  // LinkState is a UUID; map a readable label to a stable UUID so test keys stay legible.
  private def ls(label: String): LinkState = LinkState(UUID.nameUUIDFromBytes(label.getBytes))

  override def spec =
    suite("GitHubGatewayService.sweepExpiredFlows")(
      test("removes only rows whose expiresAt <= now (Clock.instant)") {
        val anchor = Instant.parse("2026-04-25T12:00:00Z")
        for
          svc  <- ZIO.service[GitHubGatewayService]
          repo <- ZIO.service[PendingLinkFlowRepository]

          past = PendingLinkFlow(
            state = ls("past"),
            createdAt = anchor.minusSeconds(7200),
            expiresAt = anchor.minusSeconds(60)
          )
          edge = PendingLinkFlow(
            state = ls("edge"),
            createdAt = anchor.minusSeconds(7200),
            expiresAt = anchor
          )
          future = PendingLinkFlow(
            state = ls("future"),
            createdAt = anchor.minusSeconds(7200),
            expiresAt = anchor.plusSeconds(60)
          )

          _ <- repo.insert(past)
          _ <- repo.insert(edge)
          _ <- repo.insert(future)

          _ <- TestClock.setTime(anchor)
          n <- svc.sweepExpiredFlows()

          ps <- repo.findByState(ls("past"))
          es <- repo.findByState(ls("edge"))
          fs <- repo.findByState(ls("future"))
        yield assertTrue(
          n == 2,
          ps.isEmpty,
          es.isEmpty,
          fs.contains(future)
        )
      }
    ).provide(
      GitHubGatewayFixtures.testConfigLayer,
      InMemoryInstallationRepository.layer,
      InMemoryLinkedRepoRepository.layer,
      InMemoryPendingLinkFlowRepository.layer,
      InMemoryWebhookDeliveryRepository.layer,
      InMemoryGitHubAppClient.layer,
      InMemoryInstallationTokenMinter.layer,
      DeterministicSecureRandom.layer,
      GitHubGatewayService.layer
    )
