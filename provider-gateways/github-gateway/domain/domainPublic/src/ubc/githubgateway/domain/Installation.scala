package ubc.githubgateway.domain

import java.time.Instant

/** A GitHub App installation belonging to an account.
  *
  * @param id
  *   local DB surrogate primary key
  * @param ghInstallationId
  *   GitHub's numeric `installation_id`
  * @param accountLogin
  *   the owning account's login (subject to rename — use [[accountId]] for joins)
  * @param accountId
  *   GitHub's stable numeric account id
  * @param accountType
  *   whether the account is a user or organization
  * @param status
  *   active or suspended
  * @param installedAt
  *   when the link was established
  */
final case class Installation(
    id: InstallationId,
    ghInstallationId: GhInstallationId,
    accountLogin: AccountLogin,
    accountId: AccountId,
    accountType: AccountType,
    status: InstallationStatus,
    installedAt: Instant
)
