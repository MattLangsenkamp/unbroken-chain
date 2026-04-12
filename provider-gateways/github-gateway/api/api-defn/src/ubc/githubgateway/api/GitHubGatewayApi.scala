package ubc.githubgateway.api

import ubc.githubgateway.domain.*
import zio.*

// Semantic contract for the GitHub Gateway service.
// Depends only on domainPublic — nothing private leaks across the service boundary.
// Cross-compiled so Scala.js consumers (e.g. a Tyrian SPA via external-api-adapters)
// can share the same type without a second definition.
trait GitHubGatewayApi:
  def getRepo(owner: String, repoName: String): Task[GitHubRepo]
  def listRepos(owner: String): Task[List[GitHubRepo]]
  def triggerSync(owner: String, repoName: String): Task[Unit]

object GitHubGatewayApi:
  def getRepo(owner: String, repoName: String): ZIO[GitHubGatewayApi, Throwable, GitHubRepo] =
    ZIO.serviceWithZIO[GitHubGatewayApi](_.getRepo(owner, repoName))
  def listRepos(owner: String): ZIO[GitHubGatewayApi, Throwable, List[GitHubRepo]] =
    ZIO.serviceWithZIO[GitHubGatewayApi](_.listRepos(owner))
  def triggerSync(owner: String, repoName: String): ZIO[GitHubGatewayApi, Throwable, Unit] =
    ZIO.serviceWithZIO[GitHubGatewayApi](_.triggerSync(owner, repoName))
