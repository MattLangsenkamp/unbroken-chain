package ubc.githubgateway.core

import neotype.*
import ubc.common.securerandom.inmemory.DeterministicSecureRandom
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.*
import zio.*
import zio.test.*

object GitHubGatewayServiceInitiateSpec extends ZIOSpecDefault:

  override def spec =
    suite("GitHubGatewayService.initiate")(
      test("returns a LinkInitiation with deterministic state (counter-based stub)") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          out <- svc.initiate()
        yield assertTrue(
          // DeterministicSecureRandom uses a single counter shared across calls. initiate()
          // calls urlSafeRandomString once for the state (32 bytes → 43 chars), counter hits 1.
          out.state == LinkState("rnd-1-" + "A" * (43 - "rnd-1-".length))
        )
      },
      test("install URL has the form https://github.com/apps/<slug>/installations/new?state=<state>") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          out <- svc.initiate()
        yield assertTrue(
          out.installUrl == InstallUrl(
            s"https://github.com/apps/${GitHubGatewayFixtures.appSlug.unwrap}/installations/new?state=${out.state.unwrap}"
          )
        )
      },
      test("persists a PendingLinkFlow keyed on the returned state") {
        for
          svc  <- ZIO.service[GitHubGatewayService]
          out  <- svc.initiate()
          repo <- ZIO.service[PendingLinkFlowRepository]
          row  <- repo.findByState(out.state)
        yield assertTrue(
          row.isDefined,
          row.exists(_.state == out.state)
        )
      },
      test("expiresAt - createdAt == config.pendingLinkTtl, both anchored on Clock.instant") {
        for
          svc  <- ZIO.service[GitHubGatewayService]
          // Adjust the test clock to a known moment
          fixedNow = java.time.Instant.parse("2026-04-25T12:00:00Z")
          _    <- TestClock.setTime(fixedNow)
          out  <- svc.initiate()
          repo <- ZIO.service[PendingLinkFlowRepository]
          row  <- repo.findByState(out.state)
        yield assertTrue(
          out.expiresAt == fixedNow.plus(GitHubGatewayFixtures.pendingTtl),
          row.exists(_.createdAt == fixedNow),
          row.exists(_.expiresAt == fixedNow.plus(GitHubGatewayFixtures.pendingTtl))
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
