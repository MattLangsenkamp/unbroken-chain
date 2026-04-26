package ubc.githubgateway.core

import ubc.common.pagination.PageRequest
import ubc.githubgateway.core.adapters.inmemory.*
import ubc.githubgateway.core.ports.*
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant

object GitHubGatewayServiceCallbackSpec extends ZIOSpecDefault:

  private val ghInstallationId = GhInstallationId(2001L)

  private val seededInstallation = Installation(
    id               = InstallationId(0L),
    ghInstallationId = ghInstallationId,
    accountLogin     = AccountLogin("octocat"),
    accountId        = AccountId(99L),
    accountType      = AccountType.User,
    status           = InstallationStatus.Active,
    installedAt      = Instant.parse("2026-04-25T11:00:00Z")
  )

  private val seededRepos: List[(GhRepositoryId, RepoFullName)] =
    List(
      GhRepositoryId(11L) -> RepoFullName("octocat/hello-world"),
      GhRepositoryId(12L) -> RepoFullName("octocat/spoon-knife")
    )

  override def spec =
    suite("GitHubGatewayService.callback")(
      test("happy path: existing pending flow → installation upserted, repo set replaced, flow row deleted") {
        for
          svc          <- ZIO.service[GitHubGatewayService]
          ghClient     <- ZIO.service[InMemoryGitHubAppClient]
          _            <- ghClient.seedInstallation(seededInstallation)
          _            <- ghClient.seedRepos(ghInstallationId, seededRepos)

          init         <- svc.initiate()
          _            <- svc.callback(init.state, ghInstallationId)

          installRepo  <- ZIO.service[InstallationRepository]
          repoRepo     <- ZIO.service[LinkedRepoRepository]
          pendingRepo  <- ZIO.service[PendingLinkFlowRepository]

          stored       <- installRepo.findByGhInstallationId(ghInstallationId)
          repos        <- repoRepo.listAll(PageRequest.default(50))
          pending      <- pendingRepo.findByState(init.state)
        yield assertTrue(
          stored.isDefined,
          stored.exists(_.accountLogin == AccountLogin("octocat")),
          repos.items.map(_.fullName).toSet == seededRepos.map(_._2).toSet,
          repos.items.forall(r => stored.exists(_.id == r.installationId)),
          pending.isEmpty
        )
      },
      test("missing state → LinkError.StateNotFound, no pending row consumed") {
        for
          svc <- ZIO.service[GitHubGatewayService]
          err <- svc.callback(LinkState("never-existed"), ghInstallationId).flip
        yield assertTrue(err == LinkError.StateNotFound)
      },
      test("expired state → LinkError.StateExpired AND the pending row is still deleted") {
        for
          svc         <- ZIO.service[GitHubGatewayService]
          init        <- svc.initiate()
          // Time-travel past the TTL
          _           <- TestClock.adjust(TestFixtures.pendingTtl.plus(Duration.fromSeconds(1)))
          err         <- svc.callback(init.state, ghInstallationId).flip
          pendingRepo <- ZIO.service[PendingLinkFlowRepository]
          pending     <- pendingRepo.findByState(init.state)
        yield assertTrue(
          err == LinkError.StateExpired,
          pending.isEmpty
        )
      },
      test("GitHub failure on getInstallation → LinkError.GitHubFailure, AND the pending row is still deleted") {
        for
          svc         <- ZIO.service[GitHubGatewayService]
          // No seeding → InMemoryGitHubAppClient.getInstallation will fail
          init        <- svc.initiate()
          err         <- svc.callback(init.state, ghInstallationId).flip
          pendingRepo <- ZIO.service[PendingLinkFlowRepository]
          pending     <- pendingRepo.findByState(init.state)
        yield assertTrue(
          err.isInstanceOf[LinkError.GitHubFailure],
          pending.isEmpty
        )
      },
      test("re-link of an already-linked installation is idempotent: same row, repo set replaced") {
        for
          svc        <- ZIO.service[GitHubGatewayService]
          ghClient   <- ZIO.service[InMemoryGitHubAppClient]
          // First link with two repos
          _          <- ghClient.seedInstallation(seededInstallation)
          _          <- ghClient.seedRepos(ghInstallationId, seededRepos)
          init1      <- svc.initiate()
          _          <- svc.callback(init1.state, ghInstallationId)

          installRepo <- ZIO.service[InstallationRepository]
          firstStored <- installRepo.findByGhInstallationId(ghInstallationId)
          firstId      = firstStored.get.id

          // Second link with a single repo replacing the prior set
          newRepos    = List(GhRepositoryId(21L) -> RepoFullName("octocat/forked-repo"))
          _          <- ghClient.seedRepos(ghInstallationId, newRepos)
          init2      <- svc.initiate()
          _          <- svc.callback(init2.state, ghInstallationId)

          secondStored <- installRepo.findByGhInstallationId(ghInstallationId)
          repoRepo     <- ZIO.service[LinkedRepoRepository]
          repos        <- repoRepo.listAll(PageRequest.default(50))
        yield assertTrue(
          firstStored.isDefined,
          secondStored.isDefined,
          firstId == secondStored.get.id, // same local id (upsert)
          repos.items.map(_.fullName) == List(RepoFullName("octocat/forked-repo"))
        )
      },
      test("two concurrent flows complete independently") {
        // Two distinct installations linked in parallel — each round-trips through its own
        // state nonce and the in-memory adapters end up with both installations + repo sets.
        val gh1 = GhInstallationId(3001L)
        val gh2 = GhInstallationId(3002L)
        val inst1 = seededInstallation.copy(ghInstallationId = gh1, accountLogin = AccountLogin("a"))
        val inst2 = seededInstallation.copy(ghInstallationId = gh2, accountLogin = AccountLogin("b"))
        val r1 = List(GhRepositoryId(101L) -> RepoFullName("a/r1"))
        val r2 = List(GhRepositoryId(201L) -> RepoFullName("b/r1"))
        for
          svc      <- ZIO.service[GitHubGatewayService]
          ghClient <- ZIO.service[InMemoryGitHubAppClient]
          _        <- ghClient.seedInstallation(inst1)
          _        <- ghClient.seedInstallation(inst2)
          _        <- ghClient.seedRepos(gh1, r1)
          _        <- ghClient.seedRepos(gh2, r2)
          init1    <- svc.initiate()
          init2    <- svc.initiate()
          _        <- ZIO.foreachPar(List((init1.state, gh1), (init2.state, gh2))) {
                        case (state, gh) => svc.callback(state, gh)
                      }
          installRepo <- ZIO.service[InstallationRepository]
          a <- installRepo.findByGhInstallationId(gh1)
          b <- installRepo.findByGhInstallationId(gh2)
        yield assertTrue(
          a.exists(_.accountLogin == AccountLogin("a")),
          b.exists(_.accountLogin == AccountLogin("b"))
        )
      }
    ).provide(
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
