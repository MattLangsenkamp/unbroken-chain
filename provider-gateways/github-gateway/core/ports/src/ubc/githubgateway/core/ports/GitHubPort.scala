package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.*
import zio.Task

trait GitHubPort:
  def getRepo(owner: RepoOwner, name: RepoName): Task[GitHubRepo]
  def listRepos(owner: RepoOwner): Task[List[GitHubRepo]]
  def searchRepos(query: String): Task[List[GitHubRepo]]

