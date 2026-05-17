package ubc.githubgateway.server

import sttp.client3.httpclient.zio.HttpClientZioBackend
import ubc.common.securerandom.javacrypto.JavaSecureRandomGenerator
import ubc.common.{BaseTelemetry, CorsMiddleware, HikariMagnumTransactor, ServerLayers}
import ubc.githubgateway.api.internal.http.GitHubGatewayHttpController
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.core.adapters.magnum.{
  MagnumInstallationRepository,
  MagnumLinkedRepoRepository,
  MagnumPendingLinkFlowRepository,
  MagnumWebhookDeliveryRepository
}
import ubc.githubgateway.core.adapters.nimbus.NimbusJoseInstallationTokenMinter
import ubc.githubgateway.core.adapters.tapir.TapirSttpGitHubAppClient
import ubc.githubgateway.domain.internal.GitHubGatewayConfig
import zio.*
import zio.http.{Response, Routes, Server as ZioServer}

/** Bootstrap for the github-gateway service.
  *
  * Wiring only — every layer constructor lives on its own companion (config in
  * `GitHubGatewayConfig.layer`, sweeper in `GitHubGatewayService.sweeperLayer`, etc.). This
  * file just lists which adapters get wired and runs the program.
  *
  * Configuration is sourced from environment variables via
  * `ConfigProvider.envProvider.snakeCase`.
  */
object Server extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase)

  def run =
    ZIO
      .serviceWithZIO[Routes[Any, Response]](routes =>
        ZioServer.serve(routes @@ CorsMiddleware.forHosts(List("localhost", "127.0.0.1")))
      )
      .provide(
        // Inbound HTTP routes
        GitHubGatewayHttpController.layer,

        // Core service + its background sweeper
        GitHubGatewayService.layer,
        GitHubGatewayService.sweeperLayer,

        // Driven adapters
        TapirSttpGitHubAppClient.layer,
        MagnumInstallationRepository.layer,
        MagnumLinkedRepoRepository.layer,
        MagnumPendingLinkFlowRepository.layer,
        MagnumWebhookDeliveryRepository.layer,
        NimbusJoseInstallationTokenMinter.layer,
        JavaSecureRandomGenerator.live,

        // Config
        GitHubGatewayConfig.layer,

        // Infra
        HikariMagnumTransactor.layer,
        HttpClientZioBackend.layer(),

        // Server lifecycle + telemetry
        ServerLayers.serverAfterTelemetry,
        BaseTelemetry.live("github-gateway")
      )
