package ubc.githubgateway.api.endpoints

import neotype.interop.tapir.given
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.*
import ubc.common.pagination.Page
import ubc.githubgateway.api.ApiError
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given

/** The single source of truth for the GitHub Gateway's HTTP wire contract.
  *
  * Each `val` is a Tapir [[Endpoint]] — a transport-agnostic description of one route (path, method, inputs, outputs,
  * errors). Tapir owns the "endpoint" abstraction; the adapters supply the interpreters:
  *   - the server (`internal-api-adapters/http`) attaches `.zServerLogic` + `ZioHttpInterpreter`
  *   - every client (`external-api-adapters/tapir-http`, JVM and Scala.js) feeds the same vals to
  *     `SttpClientInterpreter`
  *
  * Cross-compiled (JVM + Scala.js) so the browser client shares these definitions too — no hand-written mirror. Keep
  * this module free of any interpreter dependency (no tapir-zio-http-server, no tapir-sttp-client); adapters add those.
  */
object GitHubGatewayEndpoints:

  /** Every fallible endpoint surfaces failures as `(StatusCode, ApiError)` so the JSON envelope is uniform and the
    * status code is set per error class.
    */
  type ApiErr = (StatusCode, ApiError)
  val apiErrorOut: EndpointOutput[ApiErr] = statusCode.and(jsonBody[ApiError])

  // -- Linking flow --

  val initiateEndpoint: PublicEndpoint[Unit, ApiErr, LinkInitiation, Any] =
    endpoint.post
      .in("links" / "initiate")
      .out(jsonBody[LinkInitiation])
      .errorOut(apiErrorOut)

  /** GitHub redirects the user's browser here after install. The server responds with a 302 to the configured
    * success/failure URL (errors are folded into an `?error=<code>` query on the failure URL), so this endpoint's
    * output is the redirect, not a body. Typed clients mainly use it to drive the callback path during tests.
    */
  val callbackEndpoint: PublicEndpoint[
    (LinkState, GhInstallationId, Option[String], Option[String]),
    Unit,
    (StatusCode, String),
    Any
  ] =
    endpoint.get
      .in("links" / "callback")
      .in(query[LinkState]("state"))
      .in(query[GhInstallationId]("installation_id"))
      .in(query[Option[String]]("setup_action"))
      .in(query[Option[String]]("code"))
      .out(statusCode.and(header[String](HeaderNames.Location)))

  val abandonEndpoint: PublicEndpoint[LinkState, ApiErr, Unit, Any] =
    endpoint.post
      .in("links" / path[LinkState]("state") / "abandon")
      .errorOut(apiErrorOut)

  // -- Read APIs --

  val listInstallationsEndpoint: PublicEndpoint[(Option[String], Option[Int]), ApiErr, Page[Installation], Any] =
    endpoint.get
      .in("installations")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[Installation]])
      .errorOut(apiErrorOut)

  val listInstallationReposEndpoint
      : PublicEndpoint[(GhInstallationId, Option[String], Option[Int]), ApiErr, Page[LinkedRepo], Any] =
    endpoint.get
      .in("installations" / path[GhInstallationId]("installation_id") / "repos")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[LinkedRepo]])
      .errorOut(apiErrorOut)

  val listReposEndpoint: PublicEndpoint[(Option[String], Option[Int]), ApiErr, Page[LinkedRepo], Any] =
    endpoint.get
      .in("repos")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[LinkedRepo]])
      .errorOut(apiErrorOut)

  // -- Mutations --

  val unlinkEndpoint: PublicEndpoint[GhInstallationId, ApiErr, Unit, Any] =
    endpoint.delete
      .in("installations" / path[GhInstallationId]("installation_id"))
      .errorOut(apiErrorOut)

  val reconcileEndpoint: PublicEndpoint[GhInstallationId, ApiErr, ReconcileSummary, Any] =
    endpoint.post
      .in("installations" / path[GhInstallationId]("installation_id") / "reconcile")
      .out(jsonBody[ReconcileSummary])
      .errorOut(apiErrorOut)

  // -- Webhook (server-only; GitHub posts here directly, never a typed client) --

  val webhookEndpoint: PublicEndpoint[(DeliveryId, String, String, Array[Byte]), ApiErr, Unit, Any] =
    endpoint.post
      .in("webhooks" / "github")
      // DeliveryId is GitHub's UUID; decode it at the edge so a malformed header is a 400.
      .in(header[DeliveryId]("X-GitHub-Delivery"))
      .in(header[String]("X-GitHub-Event"))
      .in(header[String]("X-Hub-Signature-256"))
      .in(byteArrayBody)
      .errorOut(apiErrorOut)

  // -- Health --

  val healthEndpoint: PublicEndpoint[Unit, ApiErr, Unit, Any] =
    endpoint.get.in("health").errorOut(apiErrorOut)
