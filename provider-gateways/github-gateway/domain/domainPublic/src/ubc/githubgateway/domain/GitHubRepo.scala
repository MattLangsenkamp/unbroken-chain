package ubc.githubgateway.domain

import neotype.*

object GitHubRepoId extends Newtype[Long]
type GitHubRepoId = GitHubRepoId.Type

object RepoName extends Newtype[String]
type RepoName = RepoName.Type

object RepoOwner extends Newtype[String]
type RepoOwner = RepoOwner.Type

object RepoUrl extends Newtype[String]
type RepoUrl = RepoUrl.Type

case class GitHubRepo(
  id: GitHubRepoId,
  name: RepoName,
  owner: RepoOwner,
  url: RepoUrl
)
