package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.*

/** HTTP-level webhook headers extracted by the driving adapter and passed to the core service for signature
  * verification + idempotency keying.
  *
  * @param deliveryId
  *   value of `X-GitHub-Delivery` (UUID assigned by GitHub)
  * @param eventType
  *   value of `X-GitHub-Event` (e.g. `installation`, `installation_repositories`)
  * @param signature
  *   value of `X-Hub-Signature-256` — full string including the `sha256=` prefix
  */
final case class WebhookHeaders(
    deliveryId: DeliveryId,
    eventType: EventType,
    signature: String
)

/** Parsed shape of an incoming GitHub webhook event. JSON parsing happens in the driving adapter (Layer 5); the core
  * service only reasons about the post-parse representation.
  *
  * Variants cover only the events the core service acts on; everything else falls through to [[Unhandled]] which is
  * logged and recorded as [[WebhookOutcome.Ignored]].
  */
enum GithubWebhookEvent:
  case InstallationDeleted(ghInstallationId: GhInstallationId)
  case InstallationSuspended(ghInstallationId: GhInstallationId)
  case InstallationUnsuspended(ghInstallationId: GhInstallationId)
  case RepositoriesAdded(
      ghInstallationId: GhInstallationId,
      added: List[(GhRepositoryId, RepoFullName)]
  )
  case RepositoriesRemoved(
      ghInstallationId: GhInstallationId,
      removed: List[GhRepositoryId]
  )
  case RepositoryRenamed(ghRepositoryId: GhRepositoryId, newFullName: RepoFullName)
  case RepositoryTransferred(ghRepositoryId: GhRepositoryId, newFullName: RepoFullName)
  case Unhandled(eventType: EventType, action: Option[EventAction])
