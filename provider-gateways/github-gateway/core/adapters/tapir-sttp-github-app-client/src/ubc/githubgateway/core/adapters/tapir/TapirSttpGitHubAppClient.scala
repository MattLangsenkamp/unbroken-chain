package ubc.githubgateway.core.adapters.tapir

import ubc.githubgateway.core.ports.GitHubAppClient
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import ubc.githubgateway.domain.internal.*
import neotype.*
import zio.*
import zio.json.*

import sttp.client3.SttpBackend
import sttp.model.{StatusCode, Uri}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.client.sttp.SttpClientInterpreter

import java.time.Instant

/** Driven adapter implementing [[GitHubAppClient]] against GitHub's REST API via Tapir endpoint DSL + sttp's ZIO
  * backend.
  *
  * Production wires the real HTTP backend (e.g. `HttpClientZioBackend`); tests use `SttpBackendStub`. The adapter is
  * unit-tested at this layer with the stub — no integration tests against real GitHub here.
  *
  * Pagination note: `listInstallationRepos` issues a single `?per_page=100&page=1` request and assumes <= 100 repos in
  * the installation. This matches the spec for this milestone — full pagination can be added later if a customer
  * exceeds the cap.
  */
final class TapirSttpGitHubAppClient(
    backend: SttpBackend[Task, Any],
    baseUri: Uri
) extends GitHubAppClient:

  import TapirSttpGitHubAppClient.*
  import GhDto.given

  // ---- endpoints ----

  private val getInstallationEndpoint: Endpoint[String, Long, (StatusCode, String), GhDto.GhInstallation, Any] =
    endpoint.get
      .securityIn(auth.bearer[String]())
      .in("app" / "installations" / path[Long]("installation_id"))
      .out(jsonBody[GhDto.GhInstallation])
      .errorOut(statusCode and stringBody)

  private val listInstallationReposEndpoint
      : Endpoint[String, (Int, Int), (StatusCode, String), GhDto.GhRepoListResponse, Any] =
    endpoint.get
      .securityIn(auth.bearer[String]())
      .in("installation" / "repositories")
      .in(query[Int]("per_page").default(100))
      .in(query[Int]("page").default(1))
      .out(jsonBody[GhDto.GhRepoListResponse])
      .errorOut(statusCode and stringBody)

  private val deleteInstallationEndpoint: Endpoint[String, Long, (StatusCode, String), StatusCode, Any] =
    endpoint.delete
      .securityIn(auth.bearer[String]())
      .in("app" / "installations" / path[Long]("installation_id"))
      .out(statusCode)
      .errorOut(statusCode and stringBody)

  private val createInstallationTokenEndpoint
      : Endpoint[String, Long, (StatusCode, String), GhDto.GhInstallationToken, Any] =
    endpoint.post
      .securityIn(auth.bearer[String]())
      .in("app" / "installations" / path[Long]("installation_id") / "access_tokens")
      .out(jsonBody[GhDto.GhInstallationToken])
      .errorOut(statusCode and stringBody)

  // ---- port methods ----

  def getInstallation(jwt: AppJwt, ghInstallationId: GhInstallationId): Task[Installation] =
    SttpClientInterpreter()
      .toSecureRequestThrowDecodeFailures(getInstallationEndpoint, Some(baseUri))
      .apply(AppJwt.unwrap(jwt))
      .apply(ghInstallationId.unwrap)
      .send(backend)
      .flatMap { resp =>
        ZIO
          .fromEither(resp.body)
          .mapError(e => new RuntimeException(s"GitHub getInstallation failed: ${e._1.code} ${e._2}"))
      }
      .flatMap(toDomainInstallation)

  def listInstallationRepos(
      token: InstallationAccessToken,
      ghInstallationId: GhInstallationId
  ): Task[List[(GhRepositoryId, RepoFullName)]] =
    SttpClientInterpreter()
      .toSecureRequestThrowDecodeFailures(listInstallationReposEndpoint, Some(baseUri))
      .apply(InstallationAccessToken.unwrap(token))
      .apply((100, 1))
      .send(backend)
      .flatMap { resp =>
        ZIO
          .fromEither(resp.body)
          .mapError(e => new RuntimeException(s"GitHub listInstallationRepos failed: ${e._1.code} ${e._2}"))
      }
      .map { listResp =>
        listResp.repositories.map(r => GhRepositoryId(r.id) -> RepoFullName(r.full_name))
      }

  def deleteInstallation(
      jwt: AppJwt,
      ghInstallationId: GhInstallationId
  ): Task[Unit] =
    SttpClientInterpreter()
      .toSecureRequestThrowDecodeFailures(deleteInstallationEndpoint, Some(baseUri))
      .apply(AppJwt.unwrap(jwt))
      .apply(ghInstallationId.unwrap)
      .send(backend)
      .flatMap { resp =>
        resp.body match
          // 204 (or any 2xx that Tapir routes to the success branch) → success
          case Right(_) => ZIO.unit
          // 404 is treated as success per GitHub's idempotency contract for unlinks
          case Left((StatusCode.NotFound, _)) => ZIO.unit
          case Left((status, body))           =>
            ZIO.fail(new RuntimeException(s"GitHub deleteInstallation failed: ${status.code} $body"))
      }

  def createInstallationToken(
      jwt: AppJwt,
      ghInstallationId: GhInstallationId
  ): Task[InstallationAccessToken] =
    SttpClientInterpreter()
      .toSecureRequestThrowDecodeFailures(createInstallationTokenEndpoint, Some(baseUri))
      .apply(AppJwt.unwrap(jwt))
      .apply(ghInstallationId.unwrap)
      .send(backend)
      .flatMap { resp =>
        ZIO
          .fromEither(resp.body)
          .mapError(e => new RuntimeException(s"GitHub createInstallationToken failed: ${e._1.code} ${e._2}"))
      }
      .map(t => InstallationAccessToken.sensitive(t.token))

  // ---- helpers ----

  private def toDomainInstallation(gh: GhDto.GhInstallation): Task[Installation] =
    ZIO.attempt {
      val status =
        if gh.suspended_at.isDefined then InstallationStatus.Suspended
        else InstallationStatus.Active
      Installation(
        id = UnpersistedInstallationId,
        ghInstallationId = GhInstallationId(gh.id),
        accountLogin = AccountLogin(gh.account.login),
        accountId = AccountId(gh.account.id),
        accountType = gh.account.accountType,
        status = status,
        installedAt = Instant.parse(gh.created_at)
      )
    }

object TapirSttpGitHubAppClient:

  /** Default GitHub REST API base. */
  val defaultBaseUri: Uri = Uri.unsafeParse("https://api.github.com")

  /** Default production layer — pins the GitHub base URI. */
  val layer: ZLayer[SttpBackend[Task, Any], Nothing, GitHubAppClient] =
    layerWithBaseUri(defaultBaseUri)

  /** Override the base URI — useful for any future config-driven base URI (for instance pointing at GitHub Enterprise
    * Server, or in tests where the stub matches a different host).
    */
  def layerWithBaseUri(uri: Uri): URLayer[SttpBackend[Task, Any], GitHubAppClient] =
    ZLayer.fromFunction((b: SttpBackend[Task, Any]) => new TapirSttpGitHubAppClient(b, uri))

  // ---- GitHub-shape DTOs (NOT in domainPublic — these are GitHub-specific) ----

  private[tapir] object GhDto:
    // Tapir Schema for the AccountType enum — string-based, matches GitHub's "User"/"Organization"
    // wire format. Provided explicitly to keep `Schema.derived` from blowing the -Xmax-inlines
    // budget on the enclosing GhInstallation case class.
    given Schema[AccountType] =
      Schema.derivedEnumeration[AccountType].defaultStringBased

    final case class GhAccount(login: String, id: Long, @jsonField("type") accountType: AccountType)
    final case class GhInstallation(
        id: Long,
        account: GhAccount,
        suspended_at: Option[String],
        created_at: String
    )
    final case class GhRepository(id: Long, full_name: String)
    final case class GhRepoListResponse(total_count: Int, repositories: List[GhRepository])
    final case class GhInstallationToken(token: String, expires_at: String)

    given JsonCodec[GhAccount]           = DeriveJsonCodec.gen
    given JsonCodec[GhInstallation]      = DeriveJsonCodec.gen
    given JsonCodec[GhRepository]        = DeriveJsonCodec.gen
    given JsonCodec[GhRepoListResponse]  = DeriveJsonCodec.gen
    given JsonCodec[GhInstallationToken] = DeriveJsonCodec.gen
