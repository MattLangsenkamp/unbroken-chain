package ubc.githubgateway.core

import ubc.githubgateway.domain.{AppId, AppSlug}
import zio.*

/** Shared test fixtures for [[GitHubGatewayService]] specs. */
object TestFixtures:

  val appSlug: AppSlug = AppSlug("unbroken-chain-app")
  val appId: AppId     = AppId(123L)

  val webhookSecret: Array[Byte] = "test-secret".getBytes("UTF-8")

  val pendingTtl: Duration = 10.minutes

  val testConfigLayer: ULayer[GitHubGatewayConfig] =
    ZLayer.succeed(
      GitHubGatewayConfig(
        appId          = appId,
        appSlug        = appSlug,
        pendingLinkTtl = pendingTtl,
        webhookSecret  = webhookSecret
      )
    )

  /** HMAC-SHA-256 hex of `body` with the configured webhook secret, prefixed with `sha256=`. */
  def signSha256Hex(secret: Array[Byte], body: Array[Byte]): String =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
    "sha256=" + mac.doFinal(body).map(b => f"$b%02x").mkString
