package ubc.githubgateway.core

import neotype.*
import ubc.common.crypto.Crypto
import ubc.common.crypto.inmemory.NoopCrypto
import ubc.common.securerandom.inmemory.DeterministicSecureRandom
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
          // DeterministicSecureRandom uses a single counter shared across calls. initiate()
          // calls urlSafeRandomString first for the state (32 bytes → 43 chars) — counter
          // hits 1 — then for the verifier — counter hits 2.
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
      test("persists a PendingLinkFlow with the SHA-256 challenge of the verifier") {
        for
          svc  <- ZIO.service[GitHubGatewayService]
          out  <- svc.initiate()
          repo <- ZIO.service[PendingLinkFlowRepository]
          row  <- repo.findByState(out.state)
          // Second counter tick: 64-byte verifier → 86-char base64url string.
          rawVerifier = "rnd-2-" + "A" * (86 - "rnd-2-".length)
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
          out.expiresAt == fixedNow.plus(GitHubGatewayFixtures.pendingTtl),
          row.exists(_.createdAt == fixedNow),
          row.exists(_.expiresAt == fixedNow.plus(GitHubGatewayFixtures.pendingTtl))
        )
      },
      test("the persisted encryptedVerifier round-trips through Crypto to the original verifier") {
        for
          svc    <- ZIO.service[GitHubGatewayService]
          out    <- svc.initiate()
          repo   <- ZIO.service[PendingLinkFlowRepository]
          crypto <- ZIO.service[Crypto]
          row    <- repo.findByState(out.state)
          decrypted <- crypto.decrypt(row.get.encryptedVerifier.unwrap)
          expectedVerifier = "rnd-2-" + "A" * (86 - "rnd-2-".length)
        yield assertTrue(decrypted == expectedVerifier)
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
