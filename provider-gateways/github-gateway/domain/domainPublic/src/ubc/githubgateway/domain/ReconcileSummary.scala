package ubc.githubgateway.domain

/** Result of `POST /installations/{id}/reconcile`: how the linked-repo set changed
  * relative to GitHub's authoritative view.
  *
  * @param added
  *   number of repos newly inserted into our mirror
  * @param removed
  *   number of repos no longer selected and deleted from our mirror
  * @param renamed
  *   number of repos whose `fullName` was updated in place (matched by GitHub repository id)
  */
final case class ReconcileSummary(
    added: Int,
    removed: Int,
    renamed: Int
)
