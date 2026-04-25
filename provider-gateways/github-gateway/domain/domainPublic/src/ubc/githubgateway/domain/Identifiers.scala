package ubc.githubgateway.domain

import neotype.*

/** Local DB surrogate primary key for an installation row. */
object InstallationId extends Newtype[Long]
type InstallationId = InstallationId.Type

/** GitHub's numeric `installation_id` (the value GitHub itself assigns and echoes back in
  * webhooks and API responses). Distinct from [[InstallationId]] which is our local surrogate.
  */
object GhInstallationId extends Newtype[Long]
type GhInstallationId = GhInstallationId.Type

/** GitHub login of the account (user or organization) that owns an installation. */
object AccountLogin extends Newtype[String]
type AccountLogin = AccountLogin.Type

/** GitHub's numeric account id (stable across login renames). */
object AccountId extends Newtype[Long]
type AccountId = AccountId.Type

/** Local DB surrogate primary key for a linked_repo row. */
object RepositoryId extends Newtype[Long]
type RepositoryId = RepositoryId.Type

/** GitHub's numeric `repository_id`. Distinct from [[RepositoryId]] which is our local surrogate. */
object GhRepositoryId extends Newtype[Long]
type GhRepositoryId = GhRepositoryId.Type

/** Repository full name in `owner/repo` form, e.g. `octocat/hello-world`. */
object RepoFullName extends Newtype[String]
type RepoFullName = RepoFullName.Type

/** Server-generated nonce used to correlate a link initiation with its callback. */
object LinkState extends Newtype[String]
type LinkState = LinkState.Type

/** PKCE code challenge: base64url(SHA256(verifier)). The verifier itself is private. */
object CodeChallenge extends Newtype[String]
type CodeChallenge = CodeChallenge.Type

/** Value of the `X-GitHub-Delivery` header — a UUID GitHub assigns to each webhook delivery. */
object DeliveryId extends Newtype[String]
type DeliveryId = DeliveryId.Type

/** Value of the `X-GitHub-Event` header, e.g. `installation`, `installation_repositories`. */
object EventType extends Newtype[String]
type EventType = EventType.Type

/** Value of the payload `action` field, e.g. `created`, `deleted`, `suspend`. */
object EventAction extends Newtype[String]
type EventAction = EventAction.Type

/** GitHub URL the user is redirected to in order to install the app. */
object InstallUrl extends Newtype[String]
type InstallUrl = InstallUrl.Type

/** GitHub App's numeric id. */
object AppId extends Newtype[Long]
type AppId = AppId.Type

/** GitHub App's URL slug. */
object AppSlug extends Newtype[String]
type AppSlug = AppSlug.Type
