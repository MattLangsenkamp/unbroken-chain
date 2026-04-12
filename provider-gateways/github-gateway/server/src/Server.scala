package ubc.githubgateway.server

import ubc.githubgateway.api.internal.http.GitHubGatewayHttpController
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.core.adapters.tapir.TapirGitHubClient
import ubc.githubgateway.core.adapters.magnum.MagnumTokenRepository
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.http.Server

object Server extends ZIOAppDefault:
  def run =
    ZIO
      .serviceWithZIO[zio.http.Routes[Any, Nothing]](routes =>
        Server.serve(routes).provide(Server.default)
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
        HttpClientZioBackend.layer(),

        // TODO: wire a real DataSource (HikariCP + config) here
        ZLayer.fail(Exception("DataSource not configured"))
      )
