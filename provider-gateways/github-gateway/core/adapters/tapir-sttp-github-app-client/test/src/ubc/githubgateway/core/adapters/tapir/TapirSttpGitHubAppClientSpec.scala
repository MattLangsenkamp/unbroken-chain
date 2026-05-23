package ubc.githubgateway.core.adapters.tapir

import ubc.githubgateway.core.ports.GitHubAppClient
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import sttp.client3.{Request, Response, SttpBackend}
import sttp.client3.testing.SttpBackendStub
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.model.StatusCode

import java.time.Instant

object TapirSttpGitHubAppClientSpec extends ZIOSpecDefault:

  // ---- shared fixtures ----

  private val jwt   = AppJwt.sensitive("the-app-jwt")
  private val token = InstallationAccessToken.sensitive("the-install-token")
  private val ghId  = GhInstallationId(12345L)

  /** Build a layer that provides an [[SttpBackend]] backed by a stub matching the given
    * partial function. Each test sets up its OWN stub so matchers are isolated.
    */
  private def stubLayer(
      matcher: PartialFunction[Request[?, ?], Response[String]]
  ): ULayer[SttpBackend[Task, Any]] =
    ZLayer.succeed(
      SttpBackendStub(new RIOMonadAsyncError[Any]).whenRequestMatchesPartial(matcher)
    )

  /** A canonical GitHub `GET /app/installations/{id}` response body for a User account. */
  private val installationUserBody: String =
    """{
      |  "id": 12345,
      |  "account": { "login": "octocat", "id": 7L, "type": "User" },
      |  "suspended_at": null,
      |  "created_at": "2026-04-25T12:00:00Z"
      |}""".stripMargin.replace("7L", "7")

  private val installationOrgBody: String =
    """{
      |  "id": 12345,
      |  "account": { "login": "acme-org", "id": 99, "type": "Organization" },
      |  "suspended_at": null,
      |  "created_at": "2026-04-25T12:00:00Z"
      |}""".stripMargin

  private val installationSuspendedBody: String =
    """{
      |  "id": 12345,
      |  "account": { "login": "octocat", "id": 7, "type": "User" },
      |  "suspended_at": "2026-04-26T01:00:00Z",
      |  "created_at": "2026-04-25T12:00:00Z"
      |}""".stripMargin

  private val installationUnknownTypeBody: String =
    """{
      |  "id": 12345,
      |  "account": { "login": "weird", "id": 7, "type": "Bot" },
      |  "suspended_at": null,
      |  "created_at": "2026-04-25T12:00:00Z"
      |}""".stripMargin

  private val reposBody: String =
    """{
      |  "total_count": 2,
      |  "repositories": [
      |    { "id": 100, "full_name": "octocat/repo-a" },
      |    { "id": 200, "full_name": "octocat/repo-b" }
      |  ]
      |}""".stripMargin

  private val tokenBody: String =
    """{
      |  "token": "v1.abc",
      |  "expires_at": "2026-04-25T13:00:00Z"
      |}""".stripMargin

  override def spec =
    suite("TapirSttpGitHubAppClient")(

      // ---- getInstallation ----

      test("getInstallation happy path: User account, Active") {
        val stub = stubLayer {
          case r if r.method.method == "GET"
            && r.uri.path == List("app", "installations", "12345") =>
            Response.ok(installationUserBody)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          got    <- client.getInstallation(jwt, ghId)
        yield assertTrue(
          got.id == InstallationId(0L),
          got.ghInstallationId == GhInstallationId(12345L),
          got.accountLogin == AccountLogin("octocat"),
          got.accountId == AccountId(7L),
          got.accountType == AccountType.User,
          got.status == InstallationStatus.Active,
          got.installedAt == Instant.parse("2026-04-25T12:00:00Z")
        )).provide(stub, TapirSttpGitHubAppClient.layer)
      },

      test("getInstallation maps account.type=Organization to AccountType.Organization") {
        val stub = stubLayer {
          case r if r.uri.path == List("app", "installations", "12345") =>
            Response.ok(installationOrgBody)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          got    <- client.getInstallation(jwt, ghId)
        yield assertTrue(
          got.accountType == AccountType.Organization,
          got.accountLogin == AccountLogin("acme-org")
        )).provide(stub, TapirSttpGitHubAppClient.layer)
      },

      test("getInstallation with non-null suspended_at yields InstallationStatus.Suspended") {
        val stub = stubLayer {
          case r if r.uri.path == List("app", "installations", "12345") =>
            Response.ok(installationSuspendedBody)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          got    <- client.getInstallation(jwt, ghId)
        yield assertTrue(got.status == InstallationStatus.Suspended))
          .provide(stub, TapirSttpGitHubAppClient.layer)
      },

      test("getInstallation with unknown account.type fails with a recognizable message") {
        val stub = stubLayer {
          case r if r.uri.path == List("app", "installations", "12345") =>
            Response.ok(installationUnknownTypeBody)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          ex     <- client.getInstallation(jwt, ghId).exit
        yield assertTrue(
          ex.isFailure,
          ex match
            case Exit.Failure(c) =>
              c.failureOption.exists(_.getMessage.toLowerCase.contains("bot"))
            case _ => false
        )).provide(stub, TapirSttpGitHubAppClient.layer)
      },

      test("getInstallation on 404 fails (no domain-error mapping at this layer)") {
        val stub = stubLayer {
          case r if r.uri.path == List("app", "installations", "12345") =>
            Response("not found", StatusCode.NotFound)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          ex     <- client.getInstallation(jwt, ghId).exit
        yield assertTrue(ex.isFailure))
          .provide(stub, TapirSttpGitHubAppClient.layer)
      },

      test("getInstallation forwards the App JWT in Authorization: Bearer <jwt>") {
        // Stub matches ONLY when the bearer header is present and equal to the jwt value.
        val stub = stubLayer {
          case r if r.uri.path == List("app", "installations", "12345")
            && r.headers.exists(h =>
              h.name.equalsIgnoreCase("Authorization")
              && h.value == s"Bearer ${AppJwt.unwrap(jwt)}"
            ) =>
            Response.ok(installationUserBody)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          got    <- client.getInstallation(jwt, ghId)
        yield assertTrue(got.ghInstallationId == GhInstallationId(12345L)))
          .provide(stub, TapirSttpGitHubAppClient.layer)
      },

      // ---- listInstallationRepos ----

      test("listInstallationRepos returns the repository list") {
        val stub = stubLayer {
          case r if r.method.method == "GET"
            && r.uri.path == List("installation", "repositories") =>
            Response.ok(reposBody)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          got    <- client.listInstallationRepos(token, ghId)
        yield assertTrue(
          got == List(
            GhRepositoryId(100L) -> RepoFullName("octocat/repo-a"),
            GhRepositoryId(200L) -> RepoFullName("octocat/repo-b")
          )
        )).provide(stub, TapirSttpGitHubAppClient.layer)
      },

      // ---- deleteInstallation ----

      test("deleteInstallation succeeds on 204 No Content") {
        val stub = stubLayer {
          case r if r.method.method == "DELETE"
            && r.uri.path == List("app", "installations", "12345") =>
            Response("", StatusCode.NoContent)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          _      <- client.deleteInstallation(jwt, ghId)
        yield assertCompletes)
          .provide(stub, TapirSttpGitHubAppClient.layer)
      },

      test("deleteInstallation succeeds on 404 (idempotency)") {
        val stub = stubLayer {
          case r if r.method.method == "DELETE"
            && r.uri.path == List("app", "installations", "12345") =>
            Response("not found", StatusCode.NotFound)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          _      <- client.deleteInstallation(jwt, ghId)
        yield assertCompletes)
          .provide(stub, TapirSttpGitHubAppClient.layer)
      },

      test("deleteInstallation fails with body included on 500") {
        val stub = stubLayer {
          case r if r.method.method == "DELETE"
            && r.uri.path == List("app", "installations", "12345") =>
            Response("kaboom", StatusCode.InternalServerError)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          ex     <- client.deleteInstallation(jwt, ghId).flip
        yield assertTrue(
          Option(ex.getMessage).exists(_.contains("kaboom"))
        )).provide(stub, TapirSttpGitHubAppClient.layer)
      },

      // ---- createInstallationToken ----

      test("createInstallationToken returns the minted access token") {
        val stub = stubLayer {
          case r if r.method.method == "POST"
            && r.uri.path == List("app", "installations", "12345", "access_tokens") =>
            Response.ok(tokenBody)
        }
        (for
          client <- ZIO.service[GitHubAppClient]
          got    <- client.createInstallationToken(jwt, ghId)
        yield assertTrue(got == InstallationAccessToken.sensitive("v1.abc")))
          .provide(stub, TapirSttpGitHubAppClient.layer)
      }
    )
