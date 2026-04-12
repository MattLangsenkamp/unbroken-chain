package ubc.githubgateway.api.internal.http

import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ziohttp.*
import zio.*
import zio.http.Routes

// Inbound HTTP adapter. Defines the Tapir endpoint shapes for this service,
// decodes requests, and immediately delegates to core. No business logic here.
object GitHubGatewayHttpController:

  private val base = endpoint.in("github-gateway")

  val getRepoEndpoint =
    base.get
      .in("repos" / path[String]("owner") / path[String]("repo"))
      .out(jsonBody[GitHubRepo])
      .errorOut(stringBody)

  val listReposEndpoint =
    base.get
      .in("users" / path[String]("owner") / "repos")
      .out(jsonBody[List[GitHubRepo]])
      .errorOut(stringBody)

  val triggerSyncEndpoint =
    base.post
      .in("repos" / path[String]("owner") / path[String]("repo") / "sync")
      .out(emptyOutput)
      .errorOut(stringBody)

  def routes(service: GitHubGatewayService): Routes[Any, Nothing] =
    val getRepo =
      ZioHttpInterpreter().toHttp(getRepoEndpoint) { (owner, repo) =>
        service
          .fetchRepo(UserId("stub-user"), RepoOwner(owner), RepoName(repo))
          .mapError(_.getMessage)
      }

    val listRepos =
      ZioHttpInterpreter().toHttp(listReposEndpoint) { owner =>
        service
          .listUserRepos(UserId("stub-user"), RepoOwner(owner))
          .mapError(_.getMessage)
      }

    val triggerSync =
      ZioHttpInterpreter().toHttp(triggerSyncEndpoint) { (owner, repo) =>
        ZIO.unit.as(()).mapError(_.getMessage)
      }

    getRepo ++ listRepos ++ triggerSync

  val layer: ZLayer[GitHubGatewayService, Nothing, Routes[Any, Nothing]] =
    ZLayer.fromFunction(routes)
