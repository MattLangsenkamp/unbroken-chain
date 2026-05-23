package ubc.githubgateway.core

import ubc.common.pagination.PageRequest
import ubc.common.securerandom.inmemory.DeterministicSecureRandom
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.*
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant

object GitHubGatewayServiceReconcileSpec extends ZIOSpecDefault:

  private val ghId        = GhInstallationId(6001L)
  private val installedAt = Instant.parse("2026-04-25T11:00:00Z")

  override def spec =
    suite("GitHubGatewayService.reconcile")(
      test("missing local installation → ReconcileError.InstallationNotFound") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          err <- svc.reconcile(GhInstallationId(99999L)).flip
        yield assertTrue(err == ReconcileError.InstallationNotFound)
      },
      test("happy path: existing installation, GitHub returns repo set, local set is replaced") {
        for
          svc         <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          repoRepo    <- ZIO.service[LinkedRepoRepository]
          ghClient    <- ZIO.service[InMemoryGitHubAppClient]

          // Seed local installation + a single existing repo row
          inst <- installRepo.upsertByGhInstallationId(
            ghId,
            AccountLogin("octocat"),
            AccountId(99L),
            AccountType.User,
            InstallationStatus.Active,
            installedAt
          )
          _ <- repoRepo.insertMany(
            inst.id,
            List(GhRepositoryId(1L) -> RepoFullName("octocat/old"))
          )

          // Seed GitHub-side: the same installation but a different repo set
          _ <- ghClient.seedInstallation(
            Installation(
              id = UnpersistedInstallationId,
              ghInstallationId = ghId,
              accountLogin = AccountLogin("octocat"),
              accountId = AccountId(99L),
              accountType = AccountType.User,
              status = InstallationStatus.Active,
              installedAt = installedAt
            )
          )
          _ <- ghClient.seedRepos(
            ghId,
            List(GhRepositoryId(2L) -> RepoFullName("octocat/new"))
          )

          summary <- svc.reconcile(ghId)
          repos   <- repoRepo.listByInstallation(inst.id, PageRequest.default(50))
        yield assertTrue(
          summary.added == 1,
          summary.removed == 1,
          summary.renamed == 0,
          repos.items.map(_.fullName) == List(RepoFullName("octocat/new"))
        )
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
      GitHubGatewayService.layer
    )
