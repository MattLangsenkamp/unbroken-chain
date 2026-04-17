package ubc.githubgateway.server

import ubc.githubgateway.api.internal.http.GitHubGatewayHttpController
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.core.adapters.tapir.TapirGitHubClient
import ubc.githubgateway.core.adapters.magnum.MagnumTokenRepository
import ubc.common.{BaseTelemetry, CorsMiddleware, HikariMagnumTransactor, ServerLayers}
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.http.{Response, Routes, Server as ZioServer}

object Server extends ZIOAppDefault:

  // Use environment variables as config source; snakeCase maps e.g.
  // postgresHost → POSTGRES_HOST, otlpEndpoint → OTLP_ENDPOINT.
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase)

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
        TapirGitHubClient.layer,
        MagnumTokenRepository.layer,

        // Infrastructure
        HikariMagnumTransactor.layer,
        HttpClientZioBackend.layer(),

        // Server lifecycle + telemetry
        ServerLayers.serverAfterTelemetry,
        BaseTelemetry.live("github-gateway")
      )
