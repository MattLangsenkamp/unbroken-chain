package ubc.githubgateway.core

import neotype.*
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.*
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

object GitHubGatewayServiceInitiateSpec extends ZIOSpecDefault:

  private def base64UrlNoPadding(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  override def spec =
    suite("GitHubGatewayService.initiate")(
      test("returns a LinkInitiation with deterministic state and verifier (counter-based stub)") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          out <- svc.initiate()
        yield assertTrue(
          // DeterministicSecureRandom uses a single counter shared across the two
          // calls, so the order is: first call -> newLinkState() -> "state-1"
          out.state == LinkState("state-1")
        )
      },
      test("install URL has the form https://github.com/apps/<slug>/installations/new?state=<state>") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          out <- svc.initiate()
        yield assertTrue(
          out.installUrl == InstallUrl(
            s"https://github.com/apps/${TestFixtures.appSlug.unwrap}/installations/new?state=${out.state.unwrap}"
          )
        )
      },
      test("persists a PendingLinkFlow with the SHA-256 challenge of the verifier") {
        for
          svc  <- ZIO.service[GitHubGatewayService]
          out  <- svc.initiate()
          repo <- ZIO.service[PendingLinkFlowRepository]
          row  <- repo.findByState(out.state)
          // DeterministicSecureRandom uses a single counter, so the second call
          // (newCodeVerifier()) generates "verifier-2-…"
          rawVerifier = "verifier-2-" + "A" * math.max(0, 43 - "verifier-2-".length)
          expectedChallenge = CodeChallenge(
            base64UrlNoPadding(
              MessageDigest.getInstance("SHA-256").digest(rawVerifier.getBytes(StandardCharsets.UTF_8))
            )
          )
        yield assertTrue(
          row.isDefined,
          row.exists(_.codeChallenge == expectedChallenge)
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
          out.expiresAt == fixedNow.plus(TestFixtures.pendingTtl),
          row.exists(_.createdAt == fixedNow),
          row.exists(_.expiresAt == fixedNow.plus(TestFixtures.pendingTtl))
        )
      },
      test("the persisted encryptedVerifier round-trips through Crypto to the original verifier") {
        for
          svc    <- ZIO.service[GitHubGatewayService]
          out    <- svc.initiate()
          repo   <- ZIO.service[PendingLinkFlowRepository]
          crypto <- ZIO.service[ubc.githubgateway.core.ports.Crypto]
          row    <- repo.findByState(out.state)
          decrypted <- crypto.decrypt(row.get.encryptedVerifier)
          expectedVerifier = "verifier-2-" + "A" * math.max(0, 43 - "verifier-2-".length)
        yield assertTrue(decrypted == expectedVerifier)
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
