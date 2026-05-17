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

object GitHubGatewayServiceWebhookSpec extends ZIOSpecDefault:

  private val ghId        = GhInstallationId(7001L)
  private val installedAt = Instant.parse("2026-04-25T11:00:00Z")

  private val rawBody: Array[Byte] = """{"action":"any","installation":{"id":7001}}""".getBytes("UTF-8")

  private def headers(deliveryId: String, eventType: String, signature: String): WebhookHeaders =
    WebhookHeaders(DeliveryId(deliveryId), EventType(eventType), signature)

  private val validSig = GitHubGatewayFixtures.signSha256Hex(GitHubGatewayFixtures.webhookSecret, rawBody)

  override def spec =
    suite("GitHubGatewayService.handleWebhook")(
      test("signature mismatch → WebhookError.InvalidSignature") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          err <- svc.handleWebhook(
                   headers("d-1", "installation", "sha256=deadbeef"),
                   rawBody,
                   GithubWebhookEvent.InstallationDeleted(ghId)
                 ).flip
        yield assertTrue(err == WebhookError.InvalidSignature)
      },
      test("signature OK + duplicate deliveryId → WebhookOutcome.Duplicate, dispatch NOT invoked") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          // Seed an installation so we can detect whether a duplicate handle re-deletes it
          _ <- installRepo.upsertByGhInstallationId(
                 ghId, AccountLogin("octocat"), AccountId(99L),
                 AccountType.User, InstallationStatus.Active, installedAt
               )
          first <- svc.handleWebhook(
                     headers("d-dup", "installation", validSig),
                     rawBody,
                     GithubWebhookEvent.InstallationDeleted(ghId)
                   )
          // After first delivery the installation is gone; re-upsert and try again
          _ <- installRepo.upsertByGhInstallationId(
                 ghId, AccountLogin("octocat"), AccountId(99L),
                 AccountType.User, InstallationStatus.Active, installedAt
               )
          second <- svc.handleWebhook(
                      headers("d-dup", "installation", validSig),
                      rawBody,
                      GithubWebhookEvent.InstallationDeleted(ghId)
                    )
          // If dispatch had been invoked the second time the installation row would be gone
          stillThere <- installRepo.findByGhInstallationId(ghId)
        yield assertTrue(
          first == WebhookOutcome.Processed,
          second == WebhookOutcome.Duplicate,
          stillThere.isDefined
        )
      },
      test("InstallationDeleted → installation row removed, outcome Processed") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          _ <- installRepo.upsertByGhInstallationId(
                 ghId, AccountLogin("octocat"), AccountId(99L),
                 AccountType.User, InstallationStatus.Active, installedAt
               )
          out   <- svc.handleWebhook(
                     headers("d-del", "installation", validSig),
                     rawBody,
                     GithubWebhookEvent.InstallationDeleted(ghId)
                   )
          after <- installRepo.findByGhInstallationId(ghId)
        yield assertTrue(out == WebhookOutcome.Processed, after.isEmpty)
      },
      test("InstallationSuspended → status updated to Suspended") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          _ <- installRepo.upsertByGhInstallationId(
                 ghId, AccountLogin("octocat"), AccountId(99L),
                 AccountType.User, InstallationStatus.Active, installedAt
               )
          out   <- svc.handleWebhook(
                     headers("d-susp", "installation", validSig),
                     rawBody,
                     GithubWebhookEvent.InstallationSuspended(ghId)
                   )
          after <- installRepo.findByGhInstallationId(ghId)
        yield assertTrue(
          out == WebhookOutcome.Processed,
          after.exists(_.status == InstallationStatus.Suspended)
        )
      },
      test("InstallationUnsuspended → status updated to Active") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          _ <- installRepo.upsertByGhInstallationId(
                 ghId, AccountLogin("octocat"), AccountId(99L),
                 AccountType.User, InstallationStatus.Suspended, installedAt
               )
          out   <- svc.handleWebhook(
                     headers("d-unsusp", "installation", validSig),
                     rawBody,
                     GithubWebhookEvent.InstallationUnsuspended(ghId)
                   )
          after <- installRepo.findByGhInstallationId(ghId)
        yield assertTrue(
          out == WebhookOutcome.Processed,
          after.exists(_.status == InstallationStatus.Active)
        )
      },
      test("RepositoriesAdded → insertMany after seeding installation, outcome Processed") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          repoRepo   <- ZIO.service[LinkedRepoRepository]
          inst <- installRepo.upsertByGhInstallationId(
                    ghId, AccountLogin("octocat"), AccountId(99L),
                    AccountType.User, InstallationStatus.Active, installedAt
                  )
          out   <- svc.handleWebhook(
                     headers("d-add", "installation_repositories", validSig),
                     rawBody,
                     GithubWebhookEvent.RepositoriesAdded(
                       ghId,
                       List(GhRepositoryId(11L) -> RepoFullName("octocat/added"))
                     )
                   )
          repos <- repoRepo.listByInstallation(inst.id, PageRequest.default(50))
        yield assertTrue(
          out == WebhookOutcome.Processed,
          repos.items.size == 1,
          repos.items.head.fullName == RepoFullName("octocat/added")
        )
      },
      test("RepositoriesAdded for unknown installation → WebhookOutcome.Failed") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          out <- svc.handleWebhook(
                   headers("d-add-unknown", "installation_repositories", validSig),
                   rawBody,
                   GithubWebhookEvent.RepositoriesAdded(
                     GhInstallationId(99999L),
                     List(GhRepositoryId(50L) -> RepoFullName("x/y"))
                   )
                 )
        yield assertTrue(out == WebhookOutcome.Failed)
      },
      test("RepositoriesRemoved → deleteByGhRepositoryIds") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          repoRepo   <- ZIO.service[LinkedRepoRepository]
          inst <- installRepo.upsertByGhInstallationId(
                    ghId, AccountLogin("octocat"), AccountId(99L),
                    AccountType.User, InstallationStatus.Active, installedAt
                  )
          _ <- repoRepo.insertMany(
                 inst.id,
                 List(
                   GhRepositoryId(20L) -> RepoFullName("octocat/keep"),
                   GhRepositoryId(21L) -> RepoFullName("octocat/drop")
                 )
               )
          out   <- svc.handleWebhook(
                     headers("d-rm", "installation_repositories", validSig),
                     rawBody,
                     GithubWebhookEvent.RepositoriesRemoved(ghId, List(GhRepositoryId(21L)))
                   )
          repos <- repoRepo.listByInstallation(inst.id, PageRequest.default(50))
        yield assertTrue(
          out == WebhookOutcome.Processed,
          repos.items.map(_.fullName) == List(RepoFullName("octocat/keep"))
        )
      },
      test("RepositoryRenamed → renameByGhRepositoryId") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          repoRepo   <- ZIO.service[LinkedRepoRepository]
          inst <- installRepo.upsertByGhInstallationId(
                    ghId, AccountLogin("octocat"), AccountId(99L),
                    AccountType.User, InstallationStatus.Active, installedAt
                  )
          _ <- repoRepo.insertMany(
                 inst.id,
                 List(GhRepositoryId(30L) -> RepoFullName("octocat/old-name"))
               )
          out   <- svc.handleWebhook(
                     headers("d-rename", "repository", validSig),
                     rawBody,
                     GithubWebhookEvent.RepositoryRenamed(GhRepositoryId(30L), RepoFullName("octocat/new-name"))
                   )
          repos <- repoRepo.listByInstallation(inst.id, PageRequest.default(50))
        yield assertTrue(
          out == WebhookOutcome.Processed,
          repos.items.map(_.fullName) == List(RepoFullName("octocat/new-name"))
        )
      },
      test("RepositoryTransferred → renameByGhRepositoryId") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          installRepo <- ZIO.service[InstallationRepository]
          repoRepo   <- ZIO.service[LinkedRepoRepository]
          inst <- installRepo.upsertByGhInstallationId(
                    ghId, AccountLogin("octocat"), AccountId(99L),
                    AccountType.User, InstallationStatus.Active, installedAt
                  )
          _ <- repoRepo.insertMany(
                 inst.id,
                 List(GhRepositoryId(40L) -> RepoFullName("orig-owner/repo"))
               )
          out   <- svc.handleWebhook(
                     headers("d-xfer", "repository", validSig),
                     rawBody,
                     GithubWebhookEvent.RepositoryTransferred(GhRepositoryId(40L), RepoFullName("new-owner/repo"))
                   )
          repos <- repoRepo.listByInstallation(inst.id, PageRequest.default(50))
        yield assertTrue(
          out == WebhookOutcome.Processed,
          repos.items.map(_.fullName) == List(RepoFullName("new-owner/repo"))
        )
      },
      test("Unhandled event → WebhookOutcome.Ignored") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          out <- svc.handleWebhook(
                   headers("d-unh", "ping", validSig),
                   rawBody,
                   GithubWebhookEvent.Unhandled(EventType("ping"), None)
                 )
        yield assertTrue(out == WebhookOutcome.Ignored)
      },
      test("delivery log gets a row with the final outcome") {
        for
          svc      <- ZIO.service[GitHubGatewayService]
          fakeLog  <- ZIO.service[InMemoryWebhookDeliveryRepository]
          out      <- svc.handleWebhook(
                        headers("d-log", "ping", validSig),
                        rawBody,
                        GithubWebhookEvent.Unhandled(EventType("ping"), None)
                      )
          stored   <- fakeLog.peek(DeliveryId("d-log"))
        yield assertTrue(
          out == WebhookOutcome.Ignored,
          stored.exists(_.outcome == WebhookOutcome.Ignored)
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
      NoopCrypto.layer,
      GitHubGatewayService.layer
    )
