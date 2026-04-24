package ubc.githubgateway.api.external.http

import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import sttp.client3.*
import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import zio.*
import zio.json.*

// Scala.js implementation of GitHubGatewayApi using sttp + FetchZioBackend.
// FetchZioBackend wraps the browser's native Fetch API, returning ZIO Task —
// matching the effect type of the port trait with no extra wrapping.
class SttpFetchGitHubGatewayClient(baseUri: Uri) extends GitHubGatewayApi:

  private val backend = FetchZioBackend()

  private def get[A](path: Uri)(using JsonDecoder[A]): Task[A] =
    basicRequest
      .get(path)
      .response(asString)
      .send(backend)
      .flatMap { resp =>
        resp.body match
          case Right(json) => ZIO.fromEither(json.fromJson[A]).mapError(Exception(_))
          case Left(err)   => ZIO.fail(Exception(s"HTTP error: $err"))
      }

  def getRepo(owner: String, repoName: String): Task[GitHubRepo] =
    get(uri"$baseUri/github-gateway/repos/$owner/$repoName")

  def listRepos(owner: String): Task[List[GitHubRepo]] =
    get(uri"$baseUri/github-gateway/users/$owner/repos")

  def triggerSync(owner: String, repoName: String): Task[Unit] =
    basicRequest
      .post(uri"$baseUri/github-gateway/repos/$owner/$repoName/sync")
      .send(backend)
      .flatMap { resp =>
        ZIO.fromEither(resp.body).mapError(e => Exception(s"HTTP error: $e")).unit
      }

object SttpFetchGitHubGatewayClient:
  def apply(baseUrl: String): SttpFetchGitHubGatewayClient =
    new SttpFetchGitHubGatewayClient(Uri.unsafeParse(baseUrl))
