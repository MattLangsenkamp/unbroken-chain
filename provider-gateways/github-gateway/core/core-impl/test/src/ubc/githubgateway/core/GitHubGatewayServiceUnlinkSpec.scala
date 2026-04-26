package ubc.githubgateway.core

import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.*
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant

object GitHubGatewayServiceUnlinkSpec extends ZIOSpecDefault:

  private val ghId       = GhInstallationId(5001L)
  private val installedAt = Instant.parse("2026-04-25T11:00:00Z")

  /** Tiny in-test fake of [[GitHubAppClient]] that returns `Left("...")` on
    * `deleteInstallation` so we can exercise the failure-mapping branch.
    */
  private val failingDeleteGitHubLayer: ULayer[GitHubAppClient] =
    ZLayer.succeed(new GitHubAppClient:
      def getInstallation(jwt: AppJwt, ghInstallationId: GhInstallationId): Task[Installation] =
        ZIO.fail(new RuntimeException("not used"))
      def listInstallationRepos(token: InstallationAccessToken, ghInstallationId: GhInstallationId)
          : Task[List[(GhRepositoryId, RepoFullName)]] =
        ZIO.succeed(Nil)
      def deleteInstallation(jwt: AppJwt, ghInstallationId: GhInstallationId)
          : Task[Either[String, Unit]] =
        ZIO.succeed(Left("github 500"))
      def createInstallationToken(jwt: AppJwt, ghInstallationId: GhInstallationId)
          : Task[InstallationAccessToken] =
        ZIO.succeed(InstallationAccessToken("ignored"))
    )

  private val happyPath = test("Right(()) → local row deleted, returns Unit") {
    for
      svc        <- ZIO.service[GitHubGatewayService]
      installRepo <- ZIO.service[InstallationRepository]
      _          <- installRepo.upsertByGhInstallationId(
                      ghId, AccountLogin("octocat"), AccountId(99L),
                      AccountType.User, InstallationStatus.Active, installedAt
                    )
      _          <- svc.unlink(ghId)
      after      <- installRepo.findByGhInstallationId(ghId)
    yield assertTrue(after.isEmpty)
  }.provide(
    TestFixtures.testConfigLayer,
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

  private val gitHubFailure = test("Left(message) → UnlinkError.GitHubFailure") {
    for
      svc <- ZIO.service[GitHubGatewayService]
      err <- svc.unlink(ghId).flip
    yield assertTrue(
      err == UnlinkError.GitHubFailure("github 500")
    )
  }.provide(
    TestFixtures.testConfigLayer,
    InMemoryInstallationRepository.layer,
    InMemoryLinkedRepoRepository.layer,
    InMemoryPendingLinkFlowRepository.layer,
    InMemoryWebhookDeliveryRepository.layer,
    failingDeleteGitHubLayer,
    InMemoryInstallationTokenMinter.layer,
    DeterministicSecureRandom.layer,
    NoopCrypto.layer,
    GitHubGatewayService.layer
  )

  private val missingRow = test("unlink on a missing installation still succeeds (deleteByGhInstallationId returns false)") {
    for
      svc <- ZIO.service[GitHubGatewayService]
      _   <- svc.unlink(GhInstallationId(99999L))
    yield assertTrue(true)
  }.provide(
    TestFixtures.testConfigLayer,
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

  override def spec =
    suite("GitHubGatewayService.unlink")(happyPath, gitHubFailure, missingRow)
