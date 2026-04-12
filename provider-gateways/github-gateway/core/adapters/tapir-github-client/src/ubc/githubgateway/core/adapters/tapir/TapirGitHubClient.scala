package ubc.githubgateway.core.adapters.tapir

import ubc.githubgateway.core.ports.GitHubPort
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.client.sttp.*
import sttp.client3.*
import zio.*

// Outbound adapter implementing GitHubPort against the real GitHub REST API.
// Endpoint definitions are deliberately simplified — actual paths and response
// shapes will be refined when we wire up real GitHub responses.
class TapirGitHubClient(backend: SttpBackend[Task, Any]) extends GitHubPort:

  private val baseUri = uri"https://api.github.com"

  private val getRepoEndpoint =
    endpoint.get
      .in("repos" / path[String]("owner") / path[String]("repo"))
      .out(jsonBody[GitHubRepo])
      .errorOut(stringBody)

  private val listReposEndpoint =
    endpoint.get
      .in("users" / path[String]("username") / "repos")
      .out(jsonBody[List[GitHubRepo]])
      .errorOut(stringBody)

  private val searchReposEndpoint =
    endpoint.get
      .in("search" / "repositories")
      .in(query[String]("q"))
      .out(jsonBody[List[GitHubRepo]])
      .errorOut(stringBody)

  def getRepo(owner: RepoOwner, name: RepoName): Task[GitHubRepo] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(getRepoEndpoint, Some(baseUri))
      .apply((owner.value, name.value))
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(e => Exception(e)))

  def listRepos(owner: RepoOwner): Task[List[GitHubRepo]] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(listReposEndpoint, Some(baseUri))
      .apply(owner.value)
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(e => Exception(e)))

  def searchRepos(query: String): Task[List[GitHubRepo]] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(searchReposEndpoint, Some(baseUri))
      .apply(query)
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(e => Exception(e)))

object TapirGitHubClient:
  val layer: ZLayer[SttpBackend[Task, Any], Nothing, GitHubPort] =
    ZLayer.fromFunction(new TapirGitHubClient(_))
