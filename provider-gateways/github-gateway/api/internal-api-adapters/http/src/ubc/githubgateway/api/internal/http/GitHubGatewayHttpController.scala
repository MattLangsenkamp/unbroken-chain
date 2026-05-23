package ubc.githubgateway.api.internal.http

import neotype.*
import neotype.interop.tapir.given
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ziohttp.*
import sttp.tapir.ztapir.*
import ubc.common.TapirTracingInterceptor
import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.api.ApiError
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import ubc.githubgateway.domain.internal.{
  GitHubGatewayConfig,
  GithubWebhookEvent,
  LinkError,
  ReconcileError,
  UnlinkError,
  WebhookError,
  WebhookHeaders
}
import zio.*
import zio.http.{Response, Routes}
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Inbound HTTP adapter — exactly the routes from spec §9. Decodes requests, delegates to
  * core, encodes responses. No business logic.
  *
  * Traefik strips the `/github-gateway` prefix before forwarding here, so routes begin at
  * `/links/...`, `/installations/...`, etc. — no per-controller prefix.
  */
object GitHubGatewayHttpController:

  // -------------------------------------------------------------------------
  // Endpoints
  // -------------------------------------------------------------------------

  // All endpoints surface failures as (StatusCode, ApiError) so the JSON envelope
  // is uniform and the status code is set per error class.
  private type ApiErr = (StatusCode, ApiError)
  private val apiErrorOut = statusCode.and(jsonBody[ApiError])

  val initiateEndpoint =
    endpoint.post
      .in("links" / "initiate")
      .out(jsonBody[LinkInitiation])
      .errorOut(apiErrorOut)

  /** GitHub redirects the user here after install. We respond with a 302 to either the
    * configured success URL (on success) or the failure URL (on any LinkError). The
    * underlying error is encoded as an `?error=<code>` query string on the failure URL.
    */
  val callbackEndpoint =
    endpoint.get
      .in("links" / "callback")
      .in(query[LinkState]("state"))
      .in(query[GhInstallationId]("installation_id"))
      .in(query[Option[String]]("setup_action"))
      .in(query[Option[String]]("code"))
      // 302 redirect — body is irrelevant; we fold the redirect URL into the Location
      // header. tapir requires a header output to set Location, so we use a plain
      // (StatusCode, String) where the String is the Location header value.
      .out(statusCode and header[String](HeaderNames.Location))

  val abandonEndpoint =
    endpoint.post
      .in("links" / path[LinkState]("state") / "abandon")
      .errorOut(apiErrorOut)

  val listInstallationsEndpoint =
    endpoint.get
      .in("installations")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[Installation]])
      .errorOut(apiErrorOut)

  val listInstallationReposEndpoint =
    endpoint.get
      .in("installations" / path[GhInstallationId]("installation_id") / "repos")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[LinkedRepo]])
      .errorOut(apiErrorOut)

  val listReposEndpoint =
    endpoint.get
      .in("repos")
      .in(query[Option[String]]("cursor"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Page[LinkedRepo]])
      .errorOut(apiErrorOut)

  val unlinkEndpoint =
    endpoint.delete
      .in("installations" / path[GhInstallationId]("installation_id"))
      .errorOut(apiErrorOut)

  val reconcileEndpoint =
    endpoint.post
      .in("installations" / path[GhInstallationId]("installation_id") / "reconcile")
      .out(jsonBody[ReconcileSummary])
      .errorOut(apiErrorOut)

  val webhookEndpoint =
    endpoint.post
      .in("webhooks" / "github")
      // DeliveryId is GitHub's UUID; decode it at the edge so a malformed header is a 400.
      .in(header[DeliveryId]("X-GitHub-Delivery"))
      .in(header[String]("X-GitHub-Event"))
      .in(header[String]("X-Hub-Signature-256"))
      .in(byteArrayBody)
      .errorOut(apiErrorOut)

  val healthEndpoint =
    endpoint.get.in("health")

  // -------------------------------------------------------------------------
  // Wiring
  // -------------------------------------------------------------------------

  // Server-endpoint factories — exposed publicly so test specs can wire just the
  // endpoint they need into a TapirStubInterpreter without standing up the full Routes.

  def initiateLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    initiateEndpoint.zServerLogic[Any] { _ =>
      service.initiate()
    }

  def callbackLogic(
      service: GitHubGatewayService,
      config: GitHubGatewayConfig
  ): ZServerEndpoint[Any, Any] =
    callbackEndpoint.zServerLogic[Any] { case (state, ghInstallationId, _, _) =>
      service
        .callback(state, ghInstallationId)
        .as(StatusCode.Found -> config.linkSuccessUrl)
        .catchAll {
          case LinkError.StateNotFound =>
            ZIO.succeed(StatusCode.Found -> appendError(config.linkFailureUrl, ApiError.StateNotFound.code))
          case LinkError.StateExpired =>
            ZIO.succeed(StatusCode.Found -> appendError(config.linkFailureUrl, ApiError.StateExpired.code))
          case LinkError.GitHubFailure(_) =>
            ZIO.succeed(StatusCode.Found -> appendError(config.linkFailureUrl, "GITHUB_FAILURE"))
        }
    }

  def abandonLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    abandonEndpoint.zServerLogic[Any] { state =>
      service.abandon(state)
    }

  def listInstallationsLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    listInstallationsEndpoint.zServerLogic[Any] { case (cursor, limit) =>
      service.listInstallations(toPageRequest(cursor, limit))
    }

  def listInstallationReposLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    listInstallationReposEndpoint.zServerLogic[Any] {
      case (ghInstallationId, cursor, limit) =>
        service
          .listInstallationRepos(ghInstallationId, toPageRequest(cursor, limit))
          .mapError(reconcileErrorToApi)
    }

  def listReposLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    listReposEndpoint.zServerLogic[Any] { case (cursor, limit) =>
      service.listRepos(toPageRequest(cursor, limit))
    }

  def unlinkLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    unlinkEndpoint.zServerLogic[Any] { ghInstallationId =>
      service.unlink(ghInstallationId).mapError(unlinkErrorToApi)
    }

  def reconcileLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    reconcileEndpoint.zServerLogic[Any] { ghInstallationId =>
      service.reconcile(ghInstallationId).mapError(reconcileErrorToApi)
    }

  def webhookLogic(service: GitHubGatewayService): ZServerEndpoint[Any, Any] =
    webhookEndpoint.zServerLogic[Any] { case (deliveryId, eventType, signature, body) =>
      val headers = WebhookHeaders(
        deliveryId = deliveryId,
        eventType  = EventType(eventType),
        signature  = signature
      )
      WebhookEventParser.parse(EventType(eventType), body) match
        case Left(err) =>
          ZIO.fail((StatusCode.BadRequest, ApiError.malformedPayload(err)))
        case Right(event) =>
          service
            .handleWebhook(headers, body, event)
            .unit
            .mapError(webhookErrorToApi)
    }

  val healthLogic: ZServerEndpoint[Any, Any] =
    healthEndpoint.zServerLogic[Any](_ => ZIO.unit)

  def routes(
      service: GitHubGatewayService,
      config: GitHubGatewayConfig,
      tracing: Tracing
  ): Routes[Any, Response] =
    val interpreter = ZioHttpInterpreter(TapirTracingInterceptor.serverOptions(tracing))

    interpreter.toHttp(initiateLogic(service)) ++
      interpreter.toHttp(callbackLogic(service, config)) ++
      interpreter.toHttp(abandonLogic(service)) ++
      interpreter.toHttp(listInstallationsLogic(service)) ++
      interpreter.toHttp(listInstallationReposLogic(service)) ++
      interpreter.toHttp(listReposLogic(service)) ++
      interpreter.toHttp(unlinkLogic(service)) ++
      interpreter.toHttp(reconcileLogic(service)) ++
      interpreter.toHttp(webhookLogic(service)) ++
      interpreter.toHttp(healthLogic)

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def toPageRequest(cursor: Option[String], limit: Option[Int]): PageRequest =
    PageRequest(cursor = cursor, limit = limit.getOrElse(PageRequest.defaultLimit))

  private def reconcileErrorToApi(e: ReconcileError): ApiErr = e match
    case ReconcileError.InstallationNotFound  => StatusCode.NotFound  -> ApiError.InstallationNotFound
    case ReconcileError.GitHubFailure(message) => StatusCode.BadGateway -> ApiError.gitHubFailure(message)

  private def unlinkErrorToApi(e: UnlinkError): ApiErr = e match
    case UnlinkError.GitHubFailure(message) => StatusCode.BadGateway -> ApiError.gitHubFailure(message)

  private def webhookErrorToApi(e: WebhookError): ApiErr = e match
    case WebhookError.InvalidSignature     => StatusCode.Unauthorized -> ApiError.InvalidSignature
    case WebhookError.MalformedPayload(m)  => StatusCode.BadRequest   -> ApiError.malformedPayload(m)

  private def appendError(url: String, code: String): String =
    val sep = if url.contains("?") then "&" else "?"
    s"$url${sep}error=${URLEncoder.encode(code, StandardCharsets.UTF_8)}"

  // ApiErr is a tuple, so 'ZIO.left' needs explicit type ascription helpers.
  // We use a small extension to make the success branch type-check at callers.
  // (No-op identity, but Scala 3 needs help to widen the success branch.)

  // -------------------------------------------------------------------------
  // Layer
  // -------------------------------------------------------------------------

  val layer: ZLayer[GitHubGatewayService & GitHubGatewayConfig & Tracing, Nothing, Routes[Any, Response]] =
    ZLayer.fromFunction(routes)


/** Parses GitHub webhook JSON into [[GithubWebhookEvent]] variants the core service understands.
  *
  * Each top-level GitHub event type has a slightly different shape; we decode into small
  * private DTOs and switch on `eventType` + `action` to pick the right variant. Anything
  * we don't act on is funnelled to [[GithubWebhookEvent.Unhandled]] (the core service then
  * records `WebhookOutcome.Ignored`).
  */
private[http] object WebhookEventParser:

  import GhPayload.given

  /** Returns Left(message) if the bytes don't decode as JSON or the shape doesn't match. */
  def parse(eventType: EventType, body: Array[Byte]): Either[String, GithubWebhookEvent] =
    val text = new String(body, StandardCharsets.UTF_8)

    eventType.unwrap match
      case "installation" =>
        text.fromJson[GhPayload.InstallationPayload].map { p =>
          val ghId = GhInstallationId(p.installation.id)
          p.action match
            case "deleted"   => GithubWebhookEvent.InstallationDeleted(ghId)
            case "suspend"   => GithubWebhookEvent.InstallationSuspended(ghId)
            case "unsuspend" => GithubWebhookEvent.InstallationUnsuspended(ghId)
            case other       => GithubWebhookEvent.Unhandled(eventType, Some(EventAction(other)))
        }

      case "installation_repositories" =>
        text.fromJson[GhPayload.InstallationReposPayload].map { p =>
          val ghId = GhInstallationId(p.installation.id)
          p.action match
            case "added" =>
              val added = p.repositories_added
                .getOrElse(Nil)
                .map(r => GhRepositoryId(r.id) -> RepoFullName(r.full_name))
              GithubWebhookEvent.RepositoriesAdded(ghId, added)
            case "removed" =>
              val removed = p.repositories_removed
                .getOrElse(Nil)
                .map(r => GhRepositoryId(r.id))
              GithubWebhookEvent.RepositoriesRemoved(ghId, removed)
            case other =>
              GithubWebhookEvent.Unhandled(eventType, Some(EventAction(other)))
        }

      case "repository" =>
        text.fromJson[GhPayload.RepositoryPayload].map { p =>
          val repoId = GhRepositoryId(p.repository.id)
          val name   = RepoFullName(p.repository.full_name)
          p.action match
            case "renamed"     => GithubWebhookEvent.RepositoryRenamed(repoId, name)
            case "transferred" => GithubWebhookEvent.RepositoryTransferred(repoId, name)
            case other         => GithubWebhookEvent.Unhandled(eventType, Some(EventAction(other)))
        }

      case _other =>
        // Try to extract `action` if present; if we can't, fall through with None.
        val action = text.fromJson[GhPayload.ActionOnly].toOption.map(p => EventAction(p.action))
        Right(GithubWebhookEvent.Unhandled(eventType, action))

  /** GitHub-shape DTOs — kept private to this file. The names match the on-the-wire
    * field names exactly so DeriveJsonCodec can pick them up.
    */
  private object GhPayload:

    final case class InstallationRef(id: Long)
    final case class RepositoryRef(id: Long, full_name: String)

    final case class InstallationPayload(action: String, installation: InstallationRef)
    final case class InstallationReposPayload(
        action: String,
        installation: InstallationRef,
        repositories_added: Option[List[RepositoryRef]],
        repositories_removed: Option[List[RepositoryRef]]
    )
    final case class RepositoryPayload(action: String, repository: RepositoryRef)
    final case class ActionOnly(action: String)

    given JsonCodec[InstallationRef]          = DeriveJsonCodec.gen
    given JsonCodec[RepositoryRef]            = DeriveJsonCodec.gen
    given JsonCodec[InstallationPayload]      = DeriveJsonCodec.gen
    given JsonCodec[InstallationReposPayload] = DeriveJsonCodec.gen
    given JsonCodec[RepositoryPayload]        = DeriveJsonCodec.gen
    given JsonCodec[ActionOnly]               = DeriveJsonCodec.gen
