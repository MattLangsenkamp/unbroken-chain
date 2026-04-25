package ubc.githubgateway.core.adapters.inmemory

import ubc.common.pagination.PageRequest
import ubc.githubgateway.core.ports.InstallationRepository
import ubc.githubgateway.domain.*
import zio.*
import zio.test.*

import java.time.Instant

object InMemoryInstallationRepositorySpec extends ZIOSpecDefault:

  private val ghId1     = GhInstallationId(1001L)
  private val ghId2     = GhInstallationId(1002L)
  private val acctLogin = AccountLogin("octocat")
  private val acctId    = AccountId(42L)
  private val installedAt = Instant.parse("2026-04-25T12:00:00Z")

  override def spec =
    suite("InMemoryInstallationRepository")(
      test("upsertByGhInstallationId inserts when absent and assigns a local id") {
        for
          repo <- ZIO.service[InstallationRepository]
          inst <- repo.upsertByGhInstallationId(
                    ghId1,
                    acctLogin,
                    acctId,
                    AccountType.Organization,
                    InstallationStatus.Active,
                    installedAt
                  )
        yield assertTrue(
          inst.ghInstallationId == ghId1,
          inst.accountLogin == acctLogin,
          inst.accountId == acctId,
          inst.accountType == AccountType.Organization,
          inst.status == InstallationStatus.Active,
          inst.installedAt == installedAt,
          inst.id != InstallationId(0L)
        )
      },
      test("upsertByGhInstallationId updates the existing row when present (idempotent on ghInstallationId)") {
        for
          repo  <- ZIO.service[InstallationRepository]
          first <- repo.upsertByGhInstallationId(
                     ghId1,
                     AccountLogin("old-login"),
                     acctId,
                     AccountType.Organization,
                     InstallationStatus.Active,
                     installedAt
                   )
          second <- repo.upsertByGhInstallationId(
                      ghId1,
                      AccountLogin("new-login"),
                      acctId,
                      AccountType.Organization,
                      InstallationStatus.Suspended,
                      installedAt
                    )
        yield assertTrue(
          first.id == second.id,
          second.accountLogin == AccountLogin("new-login"),
          second.status == InstallationStatus.Suspended
        )
      },
      test("findByGhInstallationId returns None for unknown id") {
        for
          repo <- ZIO.service[InstallationRepository]
          out  <- repo.findByGhInstallationId(GhInstallationId(9999L))
        yield assertTrue(out.isEmpty)
      },
      test("findByGhInstallationId returns Some after upsert") {
        for
          repo <- ZIO.service[InstallationRepository]
          inst <- repo.upsertByGhInstallationId(
                    ghId1,
                    acctLogin,
                    acctId,
                    AccountType.Organization,
                    InstallationStatus.Active,
                    installedAt
                  )
          out  <- repo.findByGhInstallationId(ghId1)
        yield assertTrue(out.contains(inst))
      },
      test("listAll paginates results in stable order and respects limit") {
        for
          repo <- ZIO.service[InstallationRepository]
          _    <- repo.upsertByGhInstallationId(
                    ghId1,
                    acctLogin,
                    acctId,
                    AccountType.Organization,
                    InstallationStatus.Active,
                    installedAt
                  )
          _    <- repo.upsertByGhInstallationId(
                    ghId2,
                    AccountLogin("octodog"),
                    AccountId(43L),
                    AccountType.User,
                    InstallationStatus.Active,
                    installedAt
                  )
          page <- repo.listAll(PageRequest(cursor = None, limit = 1))
          full <- repo.listAll(PageRequest(cursor = None, limit = 10))
        yield assertTrue(
          page.items.size == 1,
          page.total == 2L,
          full.items.size == 2,
          full.items.map(_.ghInstallationId) == List(ghId1, ghId2)
        )
      },
      test("deleteByGhInstallationId returns true when row existed, false when absent") {
        for
          repo  <- ZIO.service[InstallationRepository]
          _     <- repo.upsertByGhInstallationId(
                     ghId1,
                     acctLogin,
                     acctId,
                     AccountType.Organization,
                     InstallationStatus.Active,
                     installedAt
                   )
          first <- repo.deleteByGhInstallationId(ghId1)
          again <- repo.deleteByGhInstallationId(ghId1)
        yield assertTrue(first, !again)
      },
      test("updateStatus mutates only the matching row") {
        for
          repo <- ZIO.service[InstallationRepository]
          _    <- repo.upsertByGhInstallationId(
                    ghId1,
                    acctLogin,
                    acctId,
                    AccountType.Organization,
                    InstallationStatus.Active,
                    installedAt
                  )
          _    <- repo.upsertByGhInstallationId(
                    ghId2,
                    AccountLogin("octodog"),
                    AccountId(43L),
                    AccountType.User,
                    InstallationStatus.Active,
                    installedAt
                  )
          _      <- repo.updateStatus(ghId1, InstallationStatus.Suspended)
          first  <- repo.findByGhInstallationId(ghId1)
          second <- repo.findByGhInstallationId(ghId2)
        yield assertTrue(
          first.exists(_.status == InstallationStatus.Suspended),
          second.exists(_.status == InstallationStatus.Active)
        )
      }
    ).provide(InMemoryInstallationRepository.layer)
