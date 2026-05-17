package ubc.githubgateway.api.internal.http

import neotype.*
import sttp.client3.*
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.*
import ubc.githubgateway.api.ApiError
import ubc.common.securerandom.inmemory.DeterministicSecureRandom
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.GitHubGatewayService
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import ubc.githubgateway.domain.internal.GitHubGatewayConfig
import ubc.common.pagination.Page
import zio.*
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.time.Instant

object GitHubGatewayHttpControllerSpec extends ZIOSpecDefault:

  // -------------------------------------------------------------------------
  // Test fixtures (kept local — duplicated from core-impl/test/GitHubGatewayFixtures so
  // this module can be tested without a moduleDep on a sibling `test` module).
  // -------------------------------------------------------------------------

  private val webhookSecret: Array[Byte] = "test-secret".getBytes("UTF-8")

  private val testConfigLayer: ULayer[GitHubGatewayConfig] =
    ZLayer.succeed(
      GitHubGatewayConfig(
        githubAppId            = AppId(123L),
        githubAppSlug          = AppSlug("unbroken-chain-app"),
        githubAppPrivateKeyPem = "test-pem",
        githubWebhookSecret    = "test-secret",
        pendingLinkTtl         = 10.minutes,
        linkSuccessUrl         = "http://localhost/link/success",
        linkFailureUrl         = "http://localhost/link/failure",
        appJwtTtl              = 9.minutes,
        sweepInterval          = 1.minute
      )
    )

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def stubBackend(endpoints: ZServerEndpoint[Any, Any]*): SttpBackend[Task, Any] =
    val stub = TapirStubInterpreter(SttpBackendStub(new RIOMonadAsyncError[Any]))
    endpoints
      .foldLeft(stub) { (acc, ep) => acc.whenServerEndpointRunLogic(ep) }
      .backend()

  private def signSha256Hex(secret: Array[Byte], body: Array[Byte]): String =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
    "sha256=" + mac.doFinal(body).map(b => f"$b%02x").mkString

  private val installationDeletedBody: Array[Byte] =
    """{"action":"deleted","installation":{"id":42}}""".getBytes(StandardCharsets.UTF_8)

  // -------------------------------------------------------------------------
  // Spec
  // -------------------------------------------------------------------------

  override def spec = suite("GitHubGatewayHttpControllerSpec")(
    test("POST /links/initiate returns 200 with LinkInitiation JSON") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.initiateLogic(svc))
        resp    <- basicRequest
                     .post(uri"http://test/links/initiate")
                     .response(asString)
                     .send(backend)
        body     = resp.body.toOption.getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.Ok,
        body.fromJson[LinkInitiation].toOption.exists(_.installUrl.unwrap.contains("github.com/apps"))
      )
    },
    test("POST /links/{state}/abandon returns 200 even for unknown state (idempotent)") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.abandonLogic(svc))
        resp    <- basicRequest
                     .post(uri"http://test/links/never-existed/abandon")
                     .send(backend)
      yield assertTrue(resp.code == StatusCode.Ok)
    },
    test("GET /links/callback with valid state redirects to success URL (302)") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        cfg     <- ZIO.service[GitHubGatewayConfig]
        gh      <- ZIO.service[InMemoryGitHubAppClient]
        // Seed an installation that the callback will look up after consuming the pending row.
        _       <- gh.seedInstallation(
                     Installation(
                       id               = InstallationId(0L),
                       ghInstallationId = GhInstallationId(99L),
                       accountLogin     = AccountLogin("octocat"),
                       accountId        = AccountId(1L),
                       accountType      = AccountType.User,
                       status           = InstallationStatus.Active,
                       installedAt      = Instant.parse("2026-04-25T00:00:00Z")
                     )
                   )
        _       <- gh.seedRepos(GhInstallationId(99L), Nil)
        // Initiate a flow to get a real state.
        init    <- svc.initiate()
        backend  = stubBackend(GitHubGatewayHttpController.callbackLogic(svc, cfg))
        resp    <- basicRequest
                     .get(uri"http://test/links/callback?state=${init.state.unwrap}&installation_id=99")
                     .followRedirects(false)
                     .send(backend)
      yield assertTrue(
        resp.code == StatusCode.Found,
        resp.header("Location").contains(cfg.linkSuccessUrl)
      )
    },
    test("GET /links/callback with missing state redirects to failure URL with error code") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        cfg     <- ZIO.service[GitHubGatewayConfig]
        backend  = stubBackend(GitHubGatewayHttpController.callbackLogic(svc, cfg))
        resp    <- basicRequest
                     .get(uri"http://test/links/callback?state=does-not-exist&installation_id=42")
                     .followRedirects(false)
                     .send(backend)
        location = resp.header("Location").getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.Found,
        location.startsWith(cfg.linkFailureUrl),
        location.contains(s"error=${ApiError.StateNotFound.code}")
      )
    },
    test("GET /installations returns paginated Page[Installation]") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.listInstallationsLogic(svc))
        resp    <- basicRequest
                     .get(uri"http://test/installations")
                     .response(asString)
                     .send(backend)
        body     = resp.body.toOption.getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.Ok,
        body.fromJson[Page[Installation]].toOption.isDefined
      )
    },
    test("GET /installations/{id}/repos returns Page[LinkedRepo] for a known installation") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        cfg     <- ZIO.service[GitHubGatewayConfig]
        gh      <- ZIO.service[InMemoryGitHubAppClient]
        _       <- gh.seedInstallation(
                     Installation(
                       id               = InstallationId(0L),
                       ghInstallationId = GhInstallationId(7L),
                       accountLogin     = AccountLogin("octocat"),
                       accountId        = AccountId(1L),
                       accountType      = AccountType.User,
                       status           = InstallationStatus.Active,
                       installedAt      = Instant.parse("2026-04-25T00:00:00Z")
                     )
                   )
        _       <- gh.seedRepos(GhInstallationId(7L), Nil)
        // Drive a callback so the local installation row exists.
        init    <- svc.initiate()
        _       <- svc.callback(init.state, GhInstallationId(7L)).orDieWith(e => new RuntimeException(e.toString))
        backend  = stubBackend(GitHubGatewayHttpController.listInstallationReposLogic(svc))
        resp    <- basicRequest
                     .get(uri"http://test/installations/7/repos")
                     .response(asString)
                     .send(backend)
        body     = resp.body.toOption.getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.Ok,
        body.fromJson[Page[LinkedRepo]].toOption.isDefined
      )
    },
    test("GET /installations/{id}/repos returns 404 for an unknown installation") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.listInstallationReposLogic(svc))
        resp    <- basicRequest
                     .get(uri"http://test/installations/9999/repos")
                     .response(asString)
                     .send(backend)
        body     = resp.body.swap.toOption.getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.NotFound,
        body.fromJson[ApiError].toOption.exists(_.code == ApiError.InstallationNotFound.code)
      )
    },
    test("GET /repos returns paginated Page[LinkedRepo]") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.listReposLogic(svc))
        resp    <- basicRequest
                     .get(uri"http://test/repos")
                     .response(asString)
                     .send(backend)
        body     = resp.body.toOption.getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.Ok,
        body.fromJson[Page[LinkedRepo]].toOption.isDefined
      )
    },
    test("DELETE /installations/{id} returns 200") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.unlinkLogic(svc))
        resp    <- basicRequest.delete(uri"http://test/installations/42").send(backend)
      yield assertTrue(resp.code == StatusCode.Ok)
    },
    test("POST /installations/{id}/reconcile returns 404 for unknown installation") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.reconcileLogic(svc))
        resp    <- basicRequest
                     .post(uri"http://test/installations/9999/reconcile")
                     .response(asString)
                     .send(backend)
      yield assertTrue(resp.code == StatusCode.NotFound)
    },
    test("POST /installations/{id}/reconcile returns ReconcileSummary for known installation") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        gh      <- ZIO.service[InMemoryGitHubAppClient]
        _       <- gh.seedInstallation(
                     Installation(
                       id               = InstallationId(0L),
                       ghInstallationId = GhInstallationId(11L),
                       accountLogin     = AccountLogin("octocat"),
                       accountId        = AccountId(1L),
                       accountType      = AccountType.User,
                       status           = InstallationStatus.Active,
                       installedAt      = Instant.parse("2026-04-25T00:00:00Z")
                     )
                   )
        _       <- gh.seedRepos(GhInstallationId(11L), Nil)
        init    <- svc.initiate()
        _       <- svc.callback(init.state, GhInstallationId(11L)).orDieWith(e => new RuntimeException(e.toString))
        backend  = stubBackend(GitHubGatewayHttpController.reconcileLogic(svc))
        resp    <- basicRequest
                     .post(uri"http://test/installations/11/reconcile")
                     .response(asString)
                     .send(backend)
        body     = resp.body.toOption.getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.Ok,
        body.fromJson[ReconcileSummary].toOption.isDefined
      )
    },
    test("POST /webhooks/github with valid HMAC signature + InstallationDeleted body returns 200") {
      val signature = signSha256Hex(webhookSecret, installationDeletedBody)
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.webhookLogic(svc))
        resp    <- basicRequest
                     .post(uri"http://test/webhooks/github")
                     .header("X-GitHub-Delivery", "delivery-1")
                     .header("X-GitHub-Event", "installation")
                     .header("X-Hub-Signature-256", signature)
                     .body(installationDeletedBody)
                     .send(backend)
      yield assertTrue(resp.code == StatusCode.Ok)
    },
    test("POST /webhooks/github with invalid signature returns 401") {
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.webhookLogic(svc))
        resp    <- basicRequest
                     .post(uri"http://test/webhooks/github")
                     .header("X-GitHub-Delivery", "delivery-2")
                     .header("X-GitHub-Event", "installation")
                     .header("X-Hub-Signature-256", "sha256=deadbeef")
                     .body(installationDeletedBody)
                     .response(asString)
                     .send(backend)
        body     = resp.body.swap.toOption.getOrElse("")
      yield assertTrue(
        resp.code == StatusCode.Unauthorized,
        body.fromJson[ApiError].toOption.exists(_.code == ApiError.InvalidSignature.code)
      )
    },
    test("POST /webhooks/github with malformed body returns 400") {
      val malformed = """{"action":"deleted"}""".getBytes(StandardCharsets.UTF_8)
      val signature = signSha256Hex(webhookSecret, malformed)
      for
        svc     <- ZIO.service[GitHubGatewayService]
        backend  = stubBackend(GitHubGatewayHttpController.webhookLogic(svc))
        resp    <- basicRequest
                     .post(uri"http://test/webhooks/github")
                     .header("X-GitHub-Delivery", "delivery-3")
                     .header("X-GitHub-Event", "installation")
                     .header("X-Hub-Signature-256", signature)
                     .body(malformed)
                     .response(asString)
                     .send(backend)
      yield assertTrue(resp.code == StatusCode.BadRequest)
    },
    test("GET /health returns 200") {
      val backend = stubBackend(GitHubGatewayHttpController.healthLogic)
      basicRequest
        .get(uri"http://test/health")
        .send(backend)
        .map(resp => assertTrue(resp.code == StatusCode.Ok))
    }
  ).provide(
    testConfigLayer,
    InMemoryInstallationRepository.layer,
    InMemoryLinkedRepoRepository.layer,
    InMemoryPendingLinkFlowRepository.layer,
    InMemoryWebhookDeliveryRepository.layer,
    InMemoryGitHubAppClient.layer,
    InMemoryInstallationTokenMinter.layer,
    DeterministicSecureRandom.layer,
    GitHubGatewayService.layer
  )
