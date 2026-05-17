package ubc.githubgateway.core

import ubc.common.crypto.inmemory.NoopCrypto
import ubc.common.securerandom.inmemory.DeterministicSecureRandom
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.LinkState
import zio.*
import zio.test.*

object GitHubGatewayServiceAbandonSpec extends ZIOSpecDefault:

  override def spec =
    suite("GitHubGatewayService.abandon")(
      test("removes the pending flow row keyed by state") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          init       <- svc.initiate()
          _          <- svc.abandon(init.state)
          repo       <- ZIO.service[PendingLinkFlowRepository]
          remaining  <- repo.findByState(init.state)
        yield assertTrue(remaining.isEmpty)
      },
      test("succeeds even when no row matches the state") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          // No initiate() call → no pending row to find → must not error
          _   <- svc.abandon(LinkState("never-existed"))
        yield assertTrue(true)
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
      NoopCrypto.layer,
      GitHubGatewayService.layer
    )
