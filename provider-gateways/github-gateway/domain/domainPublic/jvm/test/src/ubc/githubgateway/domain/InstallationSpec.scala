package ubc.githubgateway.domain

import zio.test.*

import java.time.Instant

object InstallationSpec extends ZIOSpecDefault:

  override def spec =
    suite("InstallationSpec")(
      test("Installation holds local id, GitHub installation id, account info, status, and timestamp") {
        val installedAt = Instant.parse("2026-04-25T12:00:00Z")
        val inst = Installation(
          id               = InstallationId(1L),
          ghInstallationId = GhInstallationId(987654L),
          accountLogin     = AccountLogin("octocat"),
          accountId        = AccountId(42L),
          accountType      = AccountType.User,
          status           = InstallationStatus.Active,
          installedAt      = installedAt
        )
        assertTrue(
          inst.id == InstallationId(1L),
          inst.ghInstallationId == GhInstallationId(987654L),
          inst.accountLogin == AccountLogin("octocat"),
          inst.accountId == AccountId(42L),
          inst.accountType == AccountType.User,
          inst.status == InstallationStatus.Active,
          inst.installedAt == installedAt
        )
      },
      test("Installation supports copy for status changes") {
        val original = Installation(
          id               = InstallationId(1L),
          ghInstallationId = GhInstallationId(987654L),
          accountLogin     = AccountLogin("octocat"),
          accountId        = AccountId(42L),
          accountType      = AccountType.Organization,
          status           = InstallationStatus.Active,
          installedAt      = Instant.parse("2026-04-25T12:00:00Z")
        )
        val suspended = original.copy(status = InstallationStatus.Suspended)
        assertTrue(
          suspended.status == InstallationStatus.Suspended,
          suspended.id == original.id,
          original.status == InstallationStatus.Active
        )
      },
      test("LinkedRepo holds local id, parent installation id, GitHub repo id, and full name") {
        val repo = LinkedRepo(
          id              = RepositoryId(7L),
          installationId  = InstallationId(1L),
          ghRepositoryId  = GhRepositoryId(555L),
          fullName        = RepoFullName("octocat/hello-world")
        )
        assertTrue(
          repo.id == RepositoryId(7L),
          repo.installationId == InstallationId(1L),
          repo.ghRepositoryId == GhRepositoryId(555L),
          repo.fullName == RepoFullName("octocat/hello-world")
        )
      }
    )
