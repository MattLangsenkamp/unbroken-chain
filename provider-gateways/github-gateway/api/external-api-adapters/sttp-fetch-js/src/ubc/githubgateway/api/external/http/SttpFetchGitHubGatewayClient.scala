package ubc.githubgateway.api.external.http

import neotype.*
import sttp.client3.*
import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import ubc.common.pagination.Page
import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import zio.*
import zio.json.*

/** Scala.js HTTP client for the GitHub Gateway, implementing [[GitHubGatewayApi]].
  *
  * No Tapir on the JS side — Tapir's `Schema` derivations don't all work on Scala.js.
  * Plain sttp + `FetchZioBackend` (browser Fetch API) + zio-json decoding instead.
  *
  * Routes here mirror those defined on the server controller exactly. The shape of the
  * response bodies is exercised end-to-end by the controller spec's JSON assertions, so
  * we don't keep a separate Scala.js test suite for this class.
  */
class SttpFetchGitHubGatewayClient(baseUri: Uri) extends GitHubGatewayApi:

  private val backend = FetchZioBackend()

  // ---- Helpers ----

  private def getJson[A](u: Uri)(using JsonDecoder[A]): Task[A] =
    basicRequest
      .get(u)
      .response(asString)
      .send(backend)
      .flatMap(toJson[A])

  private def postJson[A](u: Uri)(using JsonDecoder[A]): Task[A] =
    basicRequest
      .post(u)
      .response(asString)
      .send(backend)
      .flatMap(toJson[A])

  private def postUnit(u: Uri): Task[Unit] =
    basicRequest
      .post(u)
      .response(asString)
      .send(backend)
      .flatMap(toUnit)

  private def deleteUnit(u: Uri): Task[Unit] =
    basicRequest
      .delete(u)
      .response(asString)
      .send(backend)
      .flatMap(toUnit)

  private def getUnit(u: Uri): Task[Unit] =
    basicRequest
      .get(u)
      .response(asString)
      .send(backend)
      .flatMap(toUnit)

  private def toJson[A](resp: Response[Either[String, String]])(using JsonDecoder[A]): Task[A] =
    resp.body match
      case Right(body) => ZIO.fromEither(body.fromJson[A]).mapError(e => new RuntimeException(e))
      case Left(err)   => ZIO.fail(new RuntimeException(s"HTTP ${resp.code.code}: $err"))

  private def toUnit(resp: Response[Either[String, String]]): Task[Unit] =
    resp.body match
      case Right(_)  => ZIO.unit
      case Left(err) => ZIO.fail(new RuntimeException(s"HTTP ${resp.code.code}: $err"))

  private def withPaging(u: Uri, cursor: Option[String], limit: Int): Uri =
    val params = scala.collection.mutable.LinkedHashMap.empty[String, String]
    cursor.foreach(c => params += ("cursor" -> c))
    params += ("limit" -> limit.toString)
    u.addParams(params.toMap)

  // ---- API implementation ----

  def initiate(): Task[LinkInitiation] =
    postJson[LinkInitiation](uri"$baseUri/links/initiate")

  def callback(state: LinkState, ghInstallationId: GhInstallationId): Task[Unit] =
    getUnit(
      uri"$baseUri/links/callback".addParams(
        Map(
          "state"           -> state.unwrap,
          "installation_id" -> ghInstallationId.unwrap.toString
        )
      )
    )

  def abandon(state: LinkState): Task[Unit] =
    postUnit(uri"$baseUri/links/${state.unwrap}/abandon")

  def listInstallations(cursor: Option[String], limit: Int): Task[Page[Installation]] =
    getJson[Page[Installation]](withPaging(uri"$baseUri/installations", cursor, limit))

  def listInstallationRepos(
      ghInstallationId: GhInstallationId,
      cursor: Option[String],
      limit: Int
  ): Task[Page[LinkedRepo]] =
    getJson[Page[LinkedRepo]](
      withPaging(uri"$baseUri/installations/${ghInstallationId.unwrap}/repos", cursor, limit)
    )

  def listRepos(cursor: Option[String], limit: Int): Task[Page[LinkedRepo]] =
    getJson[Page[LinkedRepo]](withPaging(uri"$baseUri/repos", cursor, limit))

  def unlink(ghInstallationId: GhInstallationId): Task[Unit] =
    deleteUnit(uri"$baseUri/installations/${ghInstallationId.unwrap}")

  def reconcile(ghInstallationId: GhInstallationId): Task[ReconcileSummary] =
    postJson[ReconcileSummary](uri"$baseUri/installations/${ghInstallationId.unwrap}/reconcile")

  def health(): Task[Unit] =
    getUnit(uri"$baseUri/health")


object SttpFetchGitHubGatewayClient:

  /** Build a client from a base URL string. Tyrian instantiates this synchronously at
    * SPA init time — no ZLayer needed in the JS world.
    */
  def apply(baseUrl: String): SttpFetchGitHubGatewayClient =
    new SttpFetchGitHubGatewayClient(Uri.unsafeParse(baseUrl))
