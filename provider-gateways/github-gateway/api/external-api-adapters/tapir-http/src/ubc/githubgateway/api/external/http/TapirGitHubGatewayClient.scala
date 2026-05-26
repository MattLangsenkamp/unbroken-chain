package ubc.githubgateway.api.external.http

import sttp.client3.SttpBackend
import sttp.model.Uri
import sttp.tapir.PublicEndpoint
import sttp.tapir.client.sttp.SttpClientInterpreter
import ubc.common.pagination.Page
import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.api.endpoints.GitHubGatewayEndpoints.*
import ubc.githubgateway.domain.*
import zio.*

/** Cross-compiled (JVM + Scala.js) typed HTTP client implementing [[GitHubGatewayApi]].
  *
  * It interprets the shared [[GitHubGatewayEndpoints]] with Tapir's `SttpClientInterpreter` — the exact endpoint
  * definitions the server interprets — so the client cannot drift from the routes. The transport is injected as an
  * `SttpBackend[Task, Any]`, so the JVM (an injected sttp backend) and the browser (`FetchZioBackend`) share this
  * single implementation rather than each hand-rolling request construction.
  *
  * Errors surface as `Task` failures: a 4xx/5xx is decoded into the [[ApiError]] envelope and wrapped in a
  * `RuntimeException` carrying the stable error code + message.
  */
class TapirGitHubGatewayClient(backend: SttpBackend[Task, Any], baseUri: Uri) extends GitHubGatewayApi:

  private val interpreter = SttpClientInterpreter()

  // The one place the interpreter turns a shared endpoint + input into an sttp request.
  private def request[I, E, O](endpoint: PublicEndpoint[I, E, O, Any])(input: I) =
    interpreter.toRequestThrowDecodeFailures(endpoint, Some(baseUri)).apply(input)

  private def call[I, O](endpoint: PublicEndpoint[I, ApiErr, O, Any])(input: I): Task[O] =
    request(endpoint)(input)
      .send(backend)
      .flatMap { resp =>
        resp.body match
          case Right(value)        => ZIO.succeed(value)
          case Left((status, err)) =>
            ZIO.fail(
              new RuntimeException(s"GitHubGateway client got ${status.code}: ${err.code} ${err.message}")
            )
      }

  def initiate(): Task[LinkInitiation] =
    call(initiateEndpoint)(())

  def callback(state: LinkState, ghInstallationId: GhInstallationId): Task[Unit] =
    // The callback endpoint answers with a 302 redirect; don't chase it — issuing the
    // request and confirming it was accepted is all the trait's Unit result needs.
    request(callbackEndpoint)((state, ghInstallationId, None, None))
      .followRedirects(false)
      .send(backend)
      .unit

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

object TapirGitHubGatewayClient:

  /** JVM/ZIO entry point: build a client from an injected sttp backend. */
  def layer(baseUri: Uri): ZLayer[SttpBackend[Task, Any], Nothing, GitHubGatewayApi] =
    ZLayer.fromFunction((b: SttpBackend[Task, Any]) => new TapirGitHubGatewayClient(b, baseUri))
