package ubc.githubgateway.server

import sttp.client3.httpclient.zio.HttpClientZioBackend
import ubc.common.{BaseTelemetry, CorsMiddleware, HikariMagnumTransactor, ServerLayers}
import ubc.githubgateway.api.internal.http.GitHubGatewayHttpController
import ubc.githubgateway.core.{GitHubGatewayConfig, GitHubGatewayService}
import ubc.githubgateway.core.adapters.javacrypto.{AesGcmCrypto, JavaSecureRandomGenerator}
import ubc.githubgateway.core.adapters.magnum.{
  MagnumInstallationRepository,
  MagnumLinkedRepoRepository,
  MagnumPendingLinkFlowRepository,
  MagnumWebhookDeliveryRepository
}
import ubc.githubgateway.core.adapters.nimbus.NimbusJoseInstallationTokenMinter
import ubc.githubgateway.core.adapters.tapir.TapirSttpGitHubAppClient
import ubc.githubgateway.core.ports.{Crypto, InstallationTokenMinter}
import ubc.githubgateway.domain.{AppId, AppSlug}
import ubc.githubgateway.domain.internal.EncryptionKey
import zio.*
import zio.config.*
import zio.config.magnolia.deriveConfig
import zio.http.{Response, Routes, Server as ZioServer}

import java.nio.charset.StandardCharsets

/** Bootstrap for the github-gateway service.
  *
  * Wires the core service against its production-grade driven adapters (Magnum repositories,
  * Tapir+sttp GitHub client, Nimbus JWT minter, AES-GCM crypto, java SecureRandom),
  * exposes the HTTP controller routes, and forks a sweeper fiber that drains expired
  * pending-link rows on a fixed schedule.
  *
  * Configuration is sourced from environment variables via `ConfigProvider.envProvider.snakeCase`.
  */
object Server extends ZIOAppDefault:

  // Use environment variables as config source; snakeCase maps e.g.
  // postgresHost → POSTGRES_HOST, otlpEndpoint → OTLP_ENDPOINT.
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase)

  /** Plain mirror of the env-var contract — converted to typed domain values via
    * [[gatewayConfigLayer]] / [[tokenMinterLayer]] / [[cryptoLayer]] downstream.
    */
  final case class RawConfig(
      githubAppId: Long,
      githubAppSlug: String,
      githubAppPrivateKeyPem: String,
      githubWebhookSecret: String,
      verifierEncryptionKey: String,    // base64 32-byte AES key
      pendingLinkTtlSeconds: Long,      // typically 600 (10m)
      linkSuccessUrl: String,
      linkFailureUrl: String,
      appJwtTtlSeconds: Long,           // typically 540 (9m)
      sweepIntervalSeconds: Long        // typically 60
  )

  private val rawConfigLayer: ZLayer[Any, Config.Error, RawConfig] =
    ZLayer.fromZIO(ZIO.config[RawConfig](deriveConfig[RawConfig]))

  private val gatewayConfigLayer: ZLayer[RawConfig, Nothing, GitHubGatewayConfig] =
    ZLayer.fromFunction { (r: RawConfig) =>
      GitHubGatewayConfig(
        appId              = AppId(r.githubAppId),
        appSlug            = AppSlug(r.githubAppSlug),
        pendingLinkTtl     = Duration.fromSeconds(r.pendingLinkTtlSeconds),
        webhookSecret      = r.githubWebhookSecret.getBytes(StandardCharsets.UTF_8),
        successRedirectUrl = r.linkSuccessUrl,
        failureRedirectUrl = r.linkFailureUrl
      )
    }

  private val tokenMinterLayer: ZLayer[RawConfig, Throwable, InstallationTokenMinter] =
    ZLayer.fromZIO {
      for
        cfg    <- ZIO.service[RawConfig]
        minter <- NimbusJoseInstallationTokenMinter.fromPem(
                    AppId(cfg.githubAppId),
                    cfg.githubAppPrivateKeyPem,
                    Duration.fromSeconds(cfg.appJwtTtlSeconds)
                  )
      yield minter
    }

  private val cryptoLayer: ZLayer[RawConfig, Throwable, Crypto] =
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[RawConfig](r => AesGcmCrypto.fromKey(EncryptionKey(r.verifierEncryptionKey)))
    }

  /** Background sweeper: every `sweepIntervalSeconds`, drains expired pending-link rows.
    *
    * Forked into the layer's scope so it runs for the lifetime of the server and is
    * interrupted automatically on shutdown — no leaked fibers.
    */
  private val sweeperLayer: ZLayer[RawConfig & GitHubGatewayService, Nothing, Unit] =
    ZLayer.scoped {
      for
        cfg <- ZIO.service[RawConfig]
        svc <- ZIO.service[GitHubGatewayService]
        _   <- svc
                 .sweepExpiredFlows()
                 .repeat(Schedule.spaced(Duration.fromSeconds(cfg.sweepIntervalSeconds)))
                 .forkScoped
      yield ()
    }

  def run =
    ZIO
      .serviceWithZIO[Routes[Any, Response]](routes =>
        ZioServer.serve(routes @@ CorsMiddleware.forHosts(List("localhost")))
      )
      .provide(
        // HTTP routes wired from core
        GitHubGatewayHttpController.layer,

        // Core service — business logic
        GitHubGatewayService.layer,

        // Driven adapters
        TapirSttpGitHubAppClient.layer,
        MagnumInstallationRepository.layer,
        MagnumLinkedRepoRepository.layer,
        MagnumPendingLinkFlowRepository.layer,
        MagnumWebhookDeliveryRepository.layer,
        tokenMinterLayer,
        cryptoLayer,
        JavaSecureRandomGenerator.live,

        // Sweeper background fiber — forks itself into this layer's scope
        sweeperLayer,

        // Config
        rawConfigLayer,
        gatewayConfigLayer,

        // Infra
        HikariMagnumTransactor.layer,
        HttpClientZioBackend.layer(),

        // Server lifecycle + telemetry
        ServerLayers.serverAfterTelemetry,
        BaseTelemetry.live("github-gateway")
      )
