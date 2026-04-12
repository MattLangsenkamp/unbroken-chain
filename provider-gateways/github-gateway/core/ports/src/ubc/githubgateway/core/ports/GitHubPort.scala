package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.*
import zio.*

trait GitHubPort:
  def getRepo(owner: RepoOwner, name: RepoName): Task[GitHubRepo]
  def listRepos(owner: RepoOwner): Task[List[GitHubRepo]]
  def searchRepos(query: String): Task[List[GitHubRepo]]

object GitHubPort:
  def getRepo(owner: RepoOwner, name: RepoName): ZIO[GitHubPort, Throwable, GitHubRepo] =
    ZIO.serviceWithZIO[GitHubPort](_.getRepo(owner, name))
  def listRepos(owner: RepoOwner): ZIO[GitHubPort, Throwable, List[GitHubRepo]] =
    ZIO.serviceWithZIO[GitHubPort](_.listRepos(owner))
  def searchRepos(query: String): ZIO[GitHubPort, Throwable, List[GitHubRepo]] =
    ZIO.serviceWithZIO[GitHubPort](_.searchRepos(query))
