package ubc.githubgateway.domain

import neotype.*
import zio.test.*

object IdentifiersSpec extends ZIOSpecDefault:

  override def spec =
    suite("IdentifiersSpec")(
      test("InstallationId wraps and unwraps a Long") {
        val id: InstallationId = InstallationId(7L)
        assertTrue(id.unwrap == 7L)
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
      test("RepositoryId wraps a Long") {
        val v: RepositoryId = RepositoryId(99L)
        assertTrue(v.unwrap == 99L)
      },
      test("GhRepositoryId wraps a Long") {
        val v: GhRepositoryId = GhRepositoryId(11223344L)
        assertTrue(v.unwrap == 11223344L)
      },
      test("RepoFullName wraps owner/repo strings") {
        val v: RepoFullName = RepoFullName("octocat/hello-world")
        assertTrue(v.unwrap == "octocat/hello-world")
      },
      test("LinkState wraps an opaque nonce String") {
        val v: LinkState = LinkState("abc123nonce")
        assertTrue(v.unwrap == "abc123nonce")
      },
      test("CodeChallenge wraps a base64url SHA256 String") {
        val v: CodeChallenge = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertTrue(v.unwrap == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
      },
      test("DeliveryId wraps a UUID String") {
        val v: DeliveryId = DeliveryId("72d3162e-cc78-11e3-81ab-4c9367dc0958")
        assertTrue(v.unwrap == "72d3162e-cc78-11e3-81ab-4c9367dc0958")
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
