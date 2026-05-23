package ubc.githubgateway.domain

import neotype.*
import zio.test.*

import java.util.UUID

object IdentifiersSpec extends ZIOSpecDefault:

  override def spec =
    suite("IdentifiersSpec")(
      test("InstallationId wraps and unwraps a UUID") {
        val u  = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val id: InstallationId = InstallationId(u)
        assertTrue(id.unwrap == u)
      },
      test("GhInstallationId wraps and unwraps a Long") {
        val id: GhInstallationId = GhInstallationId(123456789L)
        assertTrue(id.unwrap == 123456789L)
      },
      test("AccountLogin wraps and unwraps a String") {
        val v: AccountLogin = AccountLogin("octocat")
        assertTrue(v.unwrap == "octocat")
      },
      test("AccountId wraps a Long") {
        val v: AccountId = AccountId(42L)
        assertTrue(v.unwrap == 42L)
      },
      test("RepositoryId wraps a UUID") {
        val u = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val v: RepositoryId = RepositoryId(u)
        assertTrue(v.unwrap == u)
      },
      test("GhRepositoryId wraps a Long") {
        val v: GhRepositoryId = GhRepositoryId(11223344L)
        assertTrue(v.unwrap == 11223344L)
      },
      test("RepoFullName wraps owner/repo strings") {
        val v: RepoFullName = RepoFullName("octocat/hello-world")
        assertTrue(v.unwrap == "octocat/hello-world")
      },
      test("LinkState wraps a UUID nonce") {
        val u = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val v: LinkState = LinkState(u)
        assertTrue(v.unwrap == u)
      },
      test("DeliveryId wraps a UUID") {
        val u = UUID.fromString("72d3162e-cc78-11e3-81ab-4c9367dc0958")
        val v: DeliveryId = DeliveryId(u)
        assertTrue(v.unwrap == u)
      },
      test("EventType wraps the X-GitHub-Event header value") {
        val v: EventType = EventType("installation")
        assertTrue(v.unwrap == "installation")
      },
      test("EventAction wraps the payload action field") {
        val v: EventAction = EventAction("created")
        assertTrue(v.unwrap == "created")
      },
      test("InstallUrl wraps a URL String") {
        val v: InstallUrl = InstallUrl("https://github.com/apps/my-app/installations/new")
        assertTrue(v.unwrap == "https://github.com/apps/my-app/installations/new")
      },
      test("AppId wraps a Long") {
        val v: AppId = AppId(987L)
        assertTrue(v.unwrap == 987L)
      },
      test("AppSlug wraps a String") {
        val v: AppSlug = AppSlug("my-app")
        assertTrue(v.unwrap == "my-app")
      }
    )
