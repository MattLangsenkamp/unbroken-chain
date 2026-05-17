package ubc.githubgateway.core

import ubc.common.securerandom.inmemory.DeterministicSecureRandom
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant

object GitHubGatewayServiceSweepSpec extends ZIOSpecDefault:

  override def spec =
    suite("GitHubGatewayService.sweepExpiredFlows")(
      test("removes only rows whose expiresAt <= now (Clock.instant)") {
        val anchor = Instant.parse("2026-04-25T12:00:00Z")
        for
          svc  <- ZIO.service[GitHubGatewayService]
          repo <- ZIO.service[PendingLinkFlowRepository]

          past   = PendingLinkFlow(
                     state     = LinkState("past"),
                     createdAt = anchor.minusSeconds(7200),
                     expiresAt = anchor.minusSeconds(60)
                   )
          edge   = PendingLinkFlow(
                     state     = LinkState("edge"),
                     createdAt = anchor.minusSeconds(7200),
                     expiresAt = anchor
                   )
          future = PendingLinkFlow(
                     state     = LinkState("future"),
                     createdAt = anchor.minusSeconds(7200),
                     expiresAt = anchor.plusSeconds(60)
                   )

          _   <- repo.insert(past)
          _   <- repo.insert(edge)
          _   <- repo.insert(future)

          _   <- TestClock.setTime(anchor)
          n   <- svc.sweepExpiredFlows()

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
