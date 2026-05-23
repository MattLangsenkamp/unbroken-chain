package ubc.githubgateway.domain

/** A repository selected for a given installation. The repo's "selected" set is owned by GitHub; we mirror it here so
  * consumers can query without round-tripping the GitHub API.
  *
  * @param id
  *   local DB surrogate primary key
  * @param installationId
  *   foreign key to [[Installation.id]]
  * @param ghRepositoryId
  *   GitHub's numeric repository id (stable across renames)
  * @param fullName
  *   `owner/repo` form; treat as a display value, not an identifier
  */
final case class LinkedRepo(
    id: RepositoryId,
    installationId: InstallationId,
    ghRepositoryId: GhRepositoryId,
    fullName: RepoFullName
)
