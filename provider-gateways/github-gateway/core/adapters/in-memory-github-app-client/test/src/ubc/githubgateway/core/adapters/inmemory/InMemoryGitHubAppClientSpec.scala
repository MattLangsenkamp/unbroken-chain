package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.GitHubAppClient
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant

object InMemoryGitHubAppClientSpec extends ZIOSpecDefault:

  private val jwt   = AppJwt("dummy-jwt")
  private val token = InstallationAccessToken("dummy-token")
  private val ghId  = GhInstallationId(7L)
  private val seeded = Installation(
    id               = InstallationId(0L),
    ghInstallationId = ghId,
    accountLogin     = AccountLogin("octocat"),
    accountId        = AccountId(99L),
    accountType      = AccountType.Organization,
    status           = InstallationStatus.Active,
    installedAt      = Instant.parse("2026-04-25T12:00:00Z")
  )

  override def spec =
    suite("InMemoryGitHubAppClient")(
      test("getInstallation returns seeded data") {
        for
          fake <- ZIO.service[InMemoryGitHubAppClient]
          _    <- fake.seedInstallation(seeded)
          got  <- fake.getInstallation(jwt, ghId)
        yield assertTrue(got == seeded)
      },
      test("getInstallation fails when not seeded") {
        for
          client <- ZIO.service[GitHubAppClient]
          ex     <- client.getInstallation(jwt, GhInstallationId(404L)).exit
        yield assertTrue(ex.isFailure)
      },
      test("listInstallationRepos returns the seeded repo set") {
        for
          fake <- ZIO.service[InMemoryGitHubAppClient]
          repos = List(
                    GhRepositoryId(1L) -> RepoFullName("o/a"),
                    GhRepositoryId(2L) -> RepoFullName("o/b")
                  )
          _    <- fake.seedRepos(ghId, repos)
          got  <- fake.listInstallationRepos(token, ghId)
        yield assertTrue(got == repos)
      },
      test("deleteInstallation returns Right(()) when seeded and Right(()) when not seeded (idempotent 404)") {
        for
          fake <- ZIO.service[InMemoryGitHubAppClient]
          _    <- fake.seedInstallation(seeded)
          a    <- fake.deleteInstallation(jwt, ghId)
          // After delete, repeating MUST still be Right(()) — that's the GitHub idempotency semantic.
          b <- fake.deleteInstallation(jwt, ghId)
          c <- fake.deleteInstallation(jwt, GhInstallationId(404L))
        yield assertTrue(a == Right(()), b == Right(()), c == Right(()))
      },
      test("createInstallationToken returns a deterministic token") {
        for
          client <- ZIO.service[GitHubAppClient]
          t1     <- client.createInstallationToken(jwt, ghId)
          t2     <- client.createInstallationToken(jwt, ghId)
        yield assertTrue(t1 == t2)
      }
    ).provide(InMemoryGitHubAppClient.layer)
