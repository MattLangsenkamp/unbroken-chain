package ubc.githubgateway.core

import ubc.githubgateway.domain.{AppId, AppSlug}
import ubc.githubgateway.domain.internal.GitHubGatewayConfig
import zio.*

/** Shared test fixtures for [[GitHubGatewayService]] specs. */
object GitHubGatewayFixtures:

  val appSlug: AppSlug = AppSlug("unbroken-chain-app")
  val appId: AppId     = AppId(123L)

  val webhookSecret: Array[Byte] = "test-secret".getBytes("UTF-8")

  val pendingTtl: Duration = 10.minutes

  val successRedirectUrl: String = "http://localhost/link/success"
  val failureRedirectUrl: String = "http://localhost/link/failure"

  val testConfigLayer: ULayer[GitHubGatewayConfig] =
    ZLayer.succeed(
      GitHubGatewayConfig(
        githubAppId = appId,
        githubAppSlug = appSlug,
        githubAppPrivateKeyPem = "test-pem", // unused in service-level tests
        githubWebhookSecret = "test-secret",
        pendingLinkTtl = pendingTtl,
        linkSuccessUrl = successRedirectUrl,
        linkFailureUrl = failureRedirectUrl,
        appJwtTtl = 9.minutes,
        sweepInterval = 1.minute
      )
    )

  /** HMAC-SHA-256 hex of `body` with the configured webhook secret, prefixed with `sha256=`. */
  def signSha256Hex(secret: Array[Byte], body: Array[Byte]): String =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
    "sha256=" + mac.doFinal(body).map(b => f"$b%02x").mkString
