package ubc.githubgateway.server

import ubc.githubgateway.api.internal.http.GitHubGatewayHttpController
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.core.adapters.tapir.TapirGitHubClient
import ubc.githubgateway.core.adapters.magnum.MagnumTokenRepository
import ubc.common.HikariMagnumTransactor
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.http.{Header, Middleware, Response, Routes, Server as ZioServer}

object Server extends ZIOAppDefault:

  // Use environment variables as config source; snakeCase maps e.g.
  // postgresHost → POSTGRES_HOST, postgresPassword → POSTGRES_PASSWORD.
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase)

  // Allow any *.localhost or bare localhost origin — covers local k8s (github.localhost)
  // and vite dev (localhost:5173) without hardcoding ports.
  private val corsConfig = Middleware.CorsConfig(
    allowedOrigin = {
      case origin @ Header.Origin.Value(_, host, _)
          if host == "localhost" || host.endsWith(".localhost") =>
        Some(Header.AccessControlAllowOrigin.Specific(origin))
      case _ => None
    }
  )

  def run =
    ZIO
      .serviceWithZIO[Routes[Any, Response]](routes =>
        ZioServer.serve(routes @@ Middleware.cors(corsConfig)).provide(ZioServer.default)
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
        HttpClientZioBackend.layer()
      )
