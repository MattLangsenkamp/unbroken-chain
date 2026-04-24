package ubc.githubgateway.api.external.http

import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import neotype.interop.tapir.given
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.client.sttp.*
import sttp.client3.*
import sttp.model.Uri
import zio.*

// Tapir + sttp JVM implementation of GitHubGatewayApi. Endpoint shapes are
// defined with the Tapir DSL and interpreted via SttpClientInterpreter —
// mirroring GitHubGatewayHttpController so client and server agree on the
// HTTP contract without sharing server-side code.
class TapirSttpGitHubGatewayClient(backend: SttpBackend[Task, Any], baseUri: Uri)
    extends GitHubGatewayApi:

  private val base = endpoint.in("github-gateway")

  private val getRepoEndpoint =
    base.get
      .in("repos" / path[String]("owner") / path[String]("repo"))
      .out(jsonBody[GitHubRepo])
      .errorOut(stringBody)

  private val listReposEndpoint =
    base.get
      .in("users" / path[String]("owner") / "repos")
      .out(jsonBody[List[GitHubRepo]])
      .errorOut(stringBody)

  private val triggerSyncEndpoint =
    base.post
      .in("repos" / path[String]("owner") / path[String]("repo") / "sync")
      .out(emptyOutput)
      .errorOut(stringBody)

  def getRepo(owner: String, repoName: String): Task[GitHubRepo] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(getRepoEndpoint, Some(baseUri))
      .apply((owner, repoName))
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(e => new Exception(e)))

  def listRepos(owner: String): Task[List[GitHubRepo]] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(listReposEndpoint, Some(baseUri))
      .apply(owner)
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(e => new Exception(e)))

  def triggerSync(owner: String, repoName: String): Task[Unit] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(triggerSyncEndpoint, Some(baseUri))
      .apply((owner, repoName))
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(e => new Exception(e)))

object TapirSttpGitHubGatewayClient:
  def layer(baseUri: Uri): ZLayer[SttpBackend[Task, Any], Nothing, GitHubGatewayApi] =
    ZLayer.fromFunction(new TapirSttpGitHubGatewayClient(_, baseUri))
