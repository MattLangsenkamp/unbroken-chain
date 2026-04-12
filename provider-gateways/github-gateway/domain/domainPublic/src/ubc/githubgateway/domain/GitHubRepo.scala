package ubc.githubgateway.domain

case class GitHubRepoId(value: Long)
case class RepoName(value: String)
case class RepoOwner(value: String)
case class RepoUrl(value: String)

case class GitHubRepo(
  id: GitHubRepoId,
  name: RepoName,
  owner: RepoOwner,
  url: RepoUrl
)
