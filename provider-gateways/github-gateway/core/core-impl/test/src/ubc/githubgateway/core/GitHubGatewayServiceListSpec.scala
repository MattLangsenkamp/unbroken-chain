package ubc.githubgateway.core

import ubc.common.pagination.PageRequest
import ubc.common.crypto.inmemory.NoopCrypto
import ubc.common.securerandom.inmemory.DeterministicSecureRandom
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.*
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant

object GitHubGatewayServiceListSpec extends ZIOSpecDefault:

  private val gh1 = GhInstallationId(4001L)
  private val gh2 = GhInstallationId(4002L)

  private val installedAt = Instant.parse("2026-04-25T11:00:00Z")

  override def spec =
    suite("GitHubGatewayService.list*")(
      test("listInstallations paginates results from the underlying repository") {
        for
          svc  <- ZIO.service[GitHubGatewayService]
          repo <- ZIO.service[InstallationRepository]
          _    <- repo.upsertByGhInstallationId(
                    gh1, AccountLogin("a"), AccountId(1L),
                    AccountType.User, InstallationStatus.Active, installedAt
                  )
          _    <- repo.upsertByGhInstallationId(
                    gh2, AccountLogin("b"), AccountId(2L),
                    AccountType.Organization, InstallationStatus.Active, installedAt
                  )
          first <- svc.listInstallations(PageRequest.default(1))
          full  <- svc.listInstallations(PageRequest.default(50))
        yield assertTrue(
          first.items.size == 1,
          first.total == 2L,
          full.items.size == 2,
          full.items.map(_.ghInstallationId) == List(gh1, gh2)
        )
      },
      test("listInstallationRepos returns repos for a known installation") {
        for
          svc       <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          repoRepo  <- ZIO.service[LinkedRepoRepository]
          inst      <- installRepo.upsertByGhInstallationId(
                          gh1, AccountLogin("a"), AccountId(1L),
                          AccountType.User, InstallationStatus.Active, installedAt
                        )
          _         <- repoRepo.insertMany(inst.id, List(GhRepositoryId(1L) -> RepoFullName("a/r1")))
          out       <- svc.listInstallationRepos(gh1, PageRequest.default(50))
        yield assertTrue(
          out.items.size == 1,
          out.items.head.fullName == RepoFullName("a/r1")
        )
      },
      test("listInstallationRepos returns ReconcileError.InstallationNotFound for unknown") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          err <- svc.listInstallationRepos(GhInstallationId(99999L), PageRequest.default(50)).flip
        yield assertTrue(err == ReconcileError.InstallationNotFound)
      },
      test("listRepos returns repos across installations") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          repoRepo   <- ZIO.service[LinkedRepoRepository]
          a <- installRepo.upsertByGhInstallationId(
                 gh1, AccountLogin("a"), AccountId(1L),
                 AccountType.User, InstallationStatus.Active, installedAt
               )
          b <- installRepo.upsertByGhInstallationId(
                 gh2, AccountLogin("b"), AccountId(2L),
                 AccountType.Organization, InstallationStatus.Active, installedAt
               )
          _ <- repoRepo.insertMany(a.id, List(GhRepositoryId(1L) -> RepoFullName("a/r1")))
          _ <- repoRepo.insertMany(b.id, List(GhRepositoryId(2L) -> RepoFullName("b/r2")))
          out <- svc.listRepos(PageRequest.default(50))
        yield assertTrue(out.items.size == 2)
      }
    ).provide(
      GitHubGatewayFixtures.testConfigLayer,
      InMemoryInstallationRepository.layer,
      InMemoryLinkedRepoRepository.layer,
      InMemoryPendingLinkFlowRepository.layer,
      InMemoryWebhookDeliveryRepository.layer,
      InMemoryGitHubAppClient.layer,
      InMemoryInstallationTokenMinter.layer,
      DeterministicSecureRandom.layer,
      NoopCrypto.layer,
      GitHubGatewayService.layer
    )
