package ubc.githubgateway.domain

import zio.test.*

import java.time.Instant
import java.util.UUID

object InstallationSpec extends ZIOSpecDefault:

  private val instId = InstallationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  private val repoId = RepositoryId(UUID.fromString("00000000-0000-0000-0000-000000000007"))

  override def spec =
    suite("InstallationSpec")(
      test("Installation holds local id, GitHub installation id, account info, status, and timestamp") {
        val installedAt = Instant.parse("2026-04-25T12:00:00Z")
        val inst = Installation(
          id               = instId,
          ghInstallationId = GhInstallationId(987654L),
          accountLogin     = AccountLogin("octocat"),
          accountId        = AccountId(42L),
          accountType      = AccountType.User,
          status           = InstallationStatus.Active,
          installedAt      = installedAt
        )
        assertTrue(
          inst.id == instId,
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
          id               = instId,
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
          id              = repoId,
          installationId  = instId,
          ghRepositoryId  = GhRepositoryId(555L),
          fullName        = RepoFullName("octocat/hello-world")
        )
        assertTrue(
          repo.id == repoId,
          repo.installationId == instId,
          repo.ghRepositoryId == GhRepositoryId(555L),
          repo.fullName == RepoFullName("octocat/hello-world")
        )
      }
    )
