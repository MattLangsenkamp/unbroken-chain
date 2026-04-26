package ubc.githubgateway.core

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
                     state             = LinkState("past"),
                     encryptedVerifier = EncryptedBytes("e1"),
                     codeChallenge     = CodeChallenge("c1"),
                     createdAt         = anchor.minusSeconds(7200),
                     expiresAt         = anchor.minusSeconds(60)
                   )
          edge   = PendingLinkFlow(
                     state             = LinkState("edge"),
                     encryptedVerifier = EncryptedBytes("e2"),
                     codeChallenge     = CodeChallenge("c2"),
                     createdAt         = anchor.minusSeconds(7200),
                     expiresAt         = anchor
                   )
          future = PendingLinkFlow(
                     state             = LinkState("future"),
                     encryptedVerifier = EncryptedBytes("e3"),
                     codeChallenge     = CodeChallenge("c3"),
                     createdAt         = anchor.minusSeconds(7200),
                     expiresAt         = anchor.plusSeconds(60)
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
      TestFixtures.testConfigLayer,
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
