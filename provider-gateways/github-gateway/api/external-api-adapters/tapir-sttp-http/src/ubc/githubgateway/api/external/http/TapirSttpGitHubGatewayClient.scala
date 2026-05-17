package ubc.githubgateway.api.external.http

import neotype.*
import neotype.interop.tapir.given
import sttp.client3.*
import sttp.model.{StatusCode, Uri}
import sttp.tapir.client.sttp.SttpClientInterpreter
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.*
import ubc.common.pagination.Page
import ubc.githubgateway.api.{ApiError, GitHubGatewayApi}
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import zio.*

/** JVM HTTP client for the GitHub Gateway. Implements [[GitHubGatewayApi]] using Tapir
  * endpoint shapes + sttp's ZIO backend. Endpoint shapes are redefined here (rather than
  * shared with the server module) to keep this module's dependency surface tight — they
  * MUST stay structurally identical to those in `internal-api-adapters/http`.
  *
  * Errors surface as `Task` failures wrapping the [[ApiError]] envelope (e.g. a 4xx response
  * decoded as JSON) — clients pattern-match on the message to decide retry / surface.
  */
class TapirSttpGitHubGatewayClient(backend: SttpBackend[Task, Any], baseUri: Uri)
    extends GitHubGatewayApi:

  private type ApiErr = (StatusCode, ApiError)
  private val apiErrorOut = statusCode.and(jsonBody[ApiError])

  // Endpoints — must mirror GitHubGatewayHttpController exactly.

  private val initiateEndpoint =
    endpoint.post
      .in("links" / "initiate")
      .out(jsonBody[LinkInitiation])
      .errorOut(apiErrorOut)

  private val callbackEndpoint =
    endpoint.get
      .in("links" / "callback")
      .in(query[LinkState]("state"))
      .in(query[GhInstallationId]("installation_id"))
      .in(query[Option[String]]("setup_action"))
      .in(query[Option[String]]("code"))
      .errorOut(apiErrorOut)

  private val abandonEndpoint =
    endpoint.post
      .in("links" / path[LinkState]("state") / "abandon")
      .errorOut(apiErrorOut)

  private val listInstallationsEndpoint =
    endpoint.get
      .in("installations")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[Installation]])
      .errorOut(apiErrorOut)

  private val listInstallationReposEndpoint =
    endpoint.get
      .in("installations" / path[GhInstallationId]("installation_id") / "repos")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[LinkedRepo]])
      .errorOut(apiErrorOut)

  private val listReposEndpoint =
    endpoint.get
      .in("repos")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[LinkedRepo]])
      .errorOut(apiErrorOut)

  private val unlinkEndpoint =
    endpoint.delete
      .in("installations" / path[GhInstallationId]("installation_id"))
      .errorOut(apiErrorOut)

  private val reconcileEndpoint =
    endpoint.post
      .in("installations" / path[GhInstallationId]("installation_id") / "reconcile")
      .out(jsonBody[ReconcileSummary])
      .errorOut(apiErrorOut)

  private val healthEndpoint =
    endpoint.get.in("health").errorOut(apiErrorOut)

  // ---- Helpers ----

  private def asTask[A](e: Either[ApiErr, A]): Task[A] =
    e match
      case Right(value)         => ZIO.succeed(value)
      case Left((status, err))  =>
        ZIO.fail(new RuntimeException(s"GitHubGateway client got ${status.code}: ${err.code} ${err.message}"))

  private def call[I, O](endpoint: PublicEndpoint[I, ApiErr, O, Any])(input: I): Task[O] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(endpoint, Some(baseUri))
      .apply(input)
      .send(backend)
      .flatMap(r => asTask(r.body))

  // ---- API implementation ----

  def initiate(): Task[LinkInitiation] =
    call(initiateEndpoint)(())

  def callback(state: LinkState, ghInstallationId: GhInstallationId): Task[Unit] =
    call(callbackEndpoint)((state, ghInstallationId, None, None))

  def abandon(state: LinkState): Task[Unit] =
    call(abandonEndpoint)(state)

  def listInstallations(cursor: Option[String], limit: Int): Task[Page[Installation]] =
    call(listInstallationsEndpoint)((cursor, Some(limit)))

  def listInstallationRepos(
      ghInstallationId: GhInstallationId,
      cursor: Option[String],
      limit: Int
  ): Task[Page[LinkedRepo]] =
    call(listInstallationReposEndpoint)((ghInstallationId, cursor, Some(limit)))

  def listRepos(cursor: Option[String], limit: Int): Task[Page[LinkedRepo]] =
    call(listReposEndpoint)((cursor, Some(limit)))

  def unlink(ghInstallationId: GhInstallationId): Task[Unit] =
    call(unlinkEndpoint)(ghInstallationId)

  def reconcile(ghInstallationId: GhInstallationId): Task[ReconcileSummary] =
    call(reconcileEndpoint)(ghInstallationId)

  def health(): Task[Unit] =
    call(healthEndpoint)(())


object TapirSttpGitHubGatewayClient:

  def layer(baseUri: Uri): ZLayer[SttpBackend[Task, Any], Nothing, GitHubGatewayApi] =
    ZLayer.fromFunction((b: SttpBackend[Task, Any]) => new TapirSttpGitHubGatewayClient(b, baseUri))
