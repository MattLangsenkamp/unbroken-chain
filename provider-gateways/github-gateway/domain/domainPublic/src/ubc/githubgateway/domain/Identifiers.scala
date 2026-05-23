package ubc.githubgateway.domain

import neotype.*

import java.util.UUID

/** Local DB surrogate primary key for an installation row. We own this identifier, so it is a
  * UUID generated app-side (never a DB sequence) — see the `relational-database-modeling` skill.
  */
object InstallationId extends Newtype[UUID]
type InstallationId = InstallationId.Type

/** Sentinel "not yet persisted" installation id (all-zero UUID).
  * [[ubc.githubgateway.core.ports.GitHubAppClient]] returns it before the local row exists; the
  * repository assigns the real id on upsert.
  */
val UnpersistedInstallationId: InstallationId = InstallationId(new UUID(0L, 0L))

/** GitHub's numeric `installation_id` (the value GitHub itself assigns and echoes back in
  * webhooks and API responses). A third-party identifier — we follow GitHub's API, which models
  * it as an integer. Distinct from [[InstallationId]] which is our local surrogate.
  */
object GhInstallationId extends Newtype[Long]
type GhInstallationId = GhInstallationId.Type

/** GitHub login of the account (user or organization) that owns an installation. */
object AccountLogin extends Newtype[String]
type AccountLogin = AccountLogin.Type

/** GitHub's numeric account id (stable across login renames). Third-party identifier — Long. */
object AccountId extends Newtype[Long]
type AccountId = AccountId.Type

/** Local DB surrogate primary key for a linked_repo row. We own this identifier, so it is a
  * UUID generated app-side.
  */
object RepositoryId extends Newtype[UUID]
type RepositoryId = RepositoryId.Type

/** GitHub's numeric `repository_id`. Third-party identifier — Long. Distinct from [[RepositoryId]]
  * which is our local surrogate.
  */
object GhRepositoryId extends Newtype[Long]
type GhRepositoryId = GhRepositoryId.Type

/** Repository full name in `owner/repo` form, e.g. `octocat/hello-world`. */
object RepoFullName extends Newtype[String]
type RepoFullName = RepoFullName.Type

/** Server-generated nonce used to correlate a link initiation with its callback. We own this
  * value, so it is a UUID generated from our `SecureRandom` port.
  */
object LinkState extends Newtype[UUID]
type LinkState = LinkState.Type

/** Value of the `X-GitHub-Delivery` header — a UUID GitHub assigns to each webhook delivery.
  * GitHub's API dictates a UUID, so we model it as one.
  */
object DeliveryId extends Newtype[UUID]
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

/** GitHub App's numeric id. Third-party identifier — Long. */
object AppId extends Newtype[Long]
type AppId = AppId.Type

/** GitHub App's URL slug. */
object AppSlug extends Newtype[String]
type AppSlug = AppSlug.Type
