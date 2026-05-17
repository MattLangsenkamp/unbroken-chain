package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.GitHubAppClient
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*

/** In-memory fake of GitHub's REST API for the App.
  *
  * Used by core service tests that need a deterministic, in-process backend — never in
  * production. State lives in two refs:
  *   - `installations` keyed by [[GhInstallationId]]
  *   - `repos`         keyed by [[GhInstallationId]]
  *
  * Tests seed state via the public `seedInstallation` / `seedRepos` methods, which are NOT
  * part of the [[GitHubAppClient]] port. The companion's `layer` exposes both the port AND
  * the concrete class so tests can `ZIO.serviceWith[InMemoryGitHubAppClient]` to seed and
  * `ZIO.serviceWith[GitHubAppClient]` to exercise the port surface.
  *
  * Choice for `deleteInstallation`: succeeds for both seeded and unseeded inputs,
  * mirroring GitHub's "404-as-success" idempotency semantic. Tests that need to assert a
  * failure can compose a custom `GitHubAppClient` that fails the task instead.
  */
final class InMemoryGitHubAppClient(
    installations: Ref[Map[GhInstallationId, Installation]],
    repos: Ref[Map[GhInstallationId, List[(GhRepositoryId, RepoFullName)]]]
) extends GitHubAppClient:

  // -- test seeding API (NOT on the port) --

  def seedInstallation(installation: Installation): UIO[Unit] =
    installations.update(_.updated(installation.ghInstallationId, installation))

  def seedRepos(
      ghInstallationId: GhInstallationId,
      repos0: List[(GhRepositoryId, RepoFullName)]
  ): UIO[Unit] =
    repos.update(_.updated(ghInstallationId, repos0))

  // -- port methods --

  def getInstallation(jwt: AppJwt, ghInstallationId: GhInstallationId): Task[Installation] =
    installations.get.flatMap { current =>
      current.get(ghInstallationId) match
        case Some(inst) => ZIO.succeed(inst)
        case None       => ZIO.fail(new RuntimeException(s"unknown installation $ghInstallationId"))
    }

  def listInstallationRepos(
      token: InstallationAccessToken,
      ghInstallationId: GhInstallationId
  ): Task[List[(GhRepositoryId, RepoFullName)]] =
    repos.get.map(_.getOrElse(ghInstallationId, Nil))

  def deleteInstallation(
      jwt: AppJwt,
      ghInstallationId: GhInstallationId
  ): Task[Unit] =
    installations.update(_ - ghInstallationId) *> repos.update(_ - ghInstallationId)

  def createInstallationToken(
      jwt: AppJwt,
      ghInstallationId: GhInstallationId
  ): Task[InstallationAccessToken] =
    ZIO.succeed(InstallationAccessToken(s"token-for-${ghInstallationId}"))

object InMemoryGitHubAppClient:
  /** Layer that provides BOTH the port-typed binding (so production-style code can depend on
    * `GitHubAppClient`) and the concrete-typed binding (so tests can call the seeding API).
    */
  val layer: ZLayer[Any, Nothing, GitHubAppClient & InMemoryGitHubAppClient] =
    ZLayer.fromZIO {
      for
        installations <- Ref.make(Map.empty[GhInstallationId, Installation])
        repos         <- Ref.make(Map.empty[GhInstallationId, List[(GhRepositoryId, RepoFullName)]])
      yield new InMemoryGitHubAppClient(installations, repos)
    }.flatMap { env =>
      val fake = env.get[InMemoryGitHubAppClient]
      ZLayer.succeedEnvironment(
        ZEnvironment[GitHubAppClient, InMemoryGitHubAppClient](fake, fake)
      )
    }
