package ubc.githubgateway.api.external.http

import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import sttp.client3.*
import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import zio.*
import zio.json.*

// Scala.js implementation of GitHubGatewayApi backed by FetchZioBackend.
// FetchZioBackend wraps the browser's Fetch API returning ZIO Task — the same
// effect type as the port trait, so no impedance mismatch at the boundary.
class GitHubGatewayJsClient(baseUri: Uri) extends GitHubGatewayApi:

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

object GitHubGatewayJsClient:
  def apply(baseUrl: String): GitHubGatewayJsClient =
    new GitHubGatewayJsClient(Uri.unsafeParse(baseUrl))
