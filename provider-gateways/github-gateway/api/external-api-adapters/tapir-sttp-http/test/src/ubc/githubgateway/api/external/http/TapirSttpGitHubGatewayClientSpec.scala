package ubc.githubgateway.api.external.http

import sttp.client3.*
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.model.{StatusCode, Uri}
import zio.*
import zio.test.*

object TapirSttpGitHubGatewayClientSpec extends ZIOSpecDefault:

  private val baseUri: Uri = Uri.unsafeParse("http://test")

  private def stubFor(json: String): SttpBackend[Task, Any] =
    SttpBackendStub(new RIOMonadAsyncError[Any]).whenAnyRequest.thenRespond(json)

  private def stubStatus(status: StatusCode, body: String): SttpBackend[Task, Any] =
    SttpBackendStub(new RIOMonadAsyncError[Any]).whenAnyRequest
      .thenRespondWithCode(status, body)

  override def spec = suite("TapirSttpGitHubGatewayClientSpec")(
    test("initiate() parses the response into LinkInitiation") {
      val state        = "11111111-1111-1111-1111-111111111111"
      val responseJson =
        s"""{"installUrl":"https://github.com/apps/x/installations/new?state=$state","state":"$state","expiresAt":"2026-04-25T12:10:00Z"}"""
      val client = new TapirSttpGitHubGatewayClient(stubFor(responseJson), baseUri)
      for out <- client.initiate()
      yield assertTrue(
        out.state.toString == state,
        out.installUrl.toString.contains("github.com/apps/x")
      )
    },
    test("listInstallations(None, 50) parses Page[Installation]") {
      val responseJson = """{"items":[],"total":0,"nextCursor":null}"""
      val client       = new TapirSttpGitHubGatewayClient(stubFor(responseJson), baseUri)
      for page <- client.listInstallations(None, 50)
      yield assertTrue(page.items.isEmpty, page.total == 0L, page.nextCursor.isEmpty)
    },
    test("unlink(...) returns Unit on 200, fails on 4xx") {
      val okClient   = new TapirSttpGitHubGatewayClient(stubFor(""), baseUri)
      val errorBody  = """{"code":"GITHUB_FAILURE","message":"boom"}"""
      val failClient = new TapirSttpGitHubGatewayClient(stubStatus(StatusCode.BadGateway, errorBody), baseUri)
      for
        ok  <- okClient.unlink(ubc.githubgateway.domain.GhInstallationId(42L)).either
        bad <- failClient.unlink(ubc.githubgateway.domain.GhInstallationId(42L)).either
      yield assertTrue(ok.isRight, bad.isLeft)
    },
    test("health() succeeds against an empty 200 body") {
      val client = new TapirSttpGitHubGatewayClient(stubFor(""), baseUri)
      for _ <- client.health() yield assertCompletes
    }
  )
