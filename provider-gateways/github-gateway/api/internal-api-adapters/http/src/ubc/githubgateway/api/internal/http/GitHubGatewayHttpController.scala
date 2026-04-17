package ubc.githubgateway.api.internal.http

import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import ubc.common.TapirTracingInterceptor
import neotype.interop.tapir.given
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ziohttp.*
import sttp.tapir.ztapir.*
import zio.*
import zio.http.{Response, Routes}
import zio.telemetry.opentelemetry.tracing.Tracing

// Inbound HTTP adapter. Defines the Tapir endpoint shapes for this service,
// decodes requests, and immediately delegates to core. No business logic here.
object GitHubGatewayHttpController:

  // Traefik strips the /github-gateway prefix before forwarding to this pod,
  // so routes here begin at /repos/... and /users/... — no prefix.
  val getRepoEndpoint =
    endpoint.get
      .in("repos" / path[String]("owner") / path[String]("repo"))
      .out(jsonBody[GitHubRepo])
      .errorOut(stringBody)

  val listReposEndpoint =
    endpoint.get
      .in("users" / path[String]("owner") / "repos")
      .out(jsonBody[List[GitHubRepo]])
      .errorOut(stringBody)

  val triggerSyncEndpoint =
    endpoint.post
      .in("repos" / path[String]("owner") / path[String]("repo") / "sync")
      .out(emptyOutput)
      .errorOut(stringBody)

  def routes(service: GitHubGatewayService, tracing: Tracing): Routes[Any, Response] =
    val interpreter = ZioHttpInterpreter(TapirTracingInterceptor.serverOptions(tracing))

    val getRepo = getRepoEndpoint.zServerLogic[Any] { case (owner, repo) =>
      service
        .fetchRepo(UserId("stub-user"), RepoOwner(owner), RepoName(repo))
        .mapError(_.getMessage)
    }

    val listRepos = listReposEndpoint.zServerLogic[Any] { owner =>
      service
        .listUserRepos(UserId("stub-user"), RepoOwner(owner))
        .mapError(_.getMessage)
    }

    val triggerSync = triggerSyncEndpoint.zServerLogic[Any] { case (owner, repo) =>
      ZIO.succeed(())
    }

    interpreter.toHttp(getRepo) ++
    interpreter.toHttp(listRepos) ++
    interpreter.toHttp(triggerSync)

  val layer: ZLayer[GitHubGatewayService & Tracing, Nothing, Routes[Any, Response]] =
    ZLayer.fromFunction(routes)
