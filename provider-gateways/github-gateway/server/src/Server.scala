package ubc.githubgateway.server

import ubc.githubgateway.api.internal.http.GitHubGatewayHttpController
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.core.adapters.tapir.TapirGitHubClient
import ubc.githubgateway.core.adapters.magnum.MagnumTokenRepository
import com.augustnagro.magnum.magzio.TransactorZIO
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.http.{Response, Routes, Server as ZioServer}

object Server extends ZIOAppDefault:
  def run =
    ZIO
      .serviceWithZIO[Routes[Any, Response]](routes =>
        ZioServer.serve(routes).provide(ZioServer.default)
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
        TransactorZIO.layer,

        // TODO: wire a real DataSource (HikariCP + config) here
        ZLayer.fail(new Exception("DataSource not configured"))
      )
