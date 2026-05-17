package ubc.githubgateway.core

import ubc.common.{ActivityEncoder, InfoLog, WarnLog}
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import zio.json.*

// Typed activity events for the GitHub Gateway core service. Each public service
// method opens with a `ZIO.logActivity(<event>)` so business-logic execution is
// observable as structured JSON in log storage.
//
// Naming convention: past-tense business facts. `LinkInitiated`, `WebhookSignatureRejected`,
// `InstallationReconciled`. Never `LinkInitiateEvent` or `LogXxx`.
//
// Domain newtypes serialise to their underlying primitive via the
// `neotype-zio-json` interop in `PublicJsonCodecs`, so the JSON wire form
// is identical to using a raw String/Long while preserving compile-time safety.
//
// Secrets MUST NOT appear in any field — events go to durable log storage.
// (Belt-and-suspenders: any field whose declared type <: Sensitive is
// automatically redacted by `ActivityEncoder` even if a developer slips up.)

// ----- Linking flow ----------------------------------------------------------

final case class LinkInitiated(state: LinkState, expiresAt: java.time.Instant)
    extends InfoLog derives ActivityEncoder

final case class CallbackReceived(state: LinkState, ghInstallationId: GhInstallationId)
    extends InfoLog derives ActivityEncoder

final case class CallbackRejectedStateNotFound(state: LinkState)
    extends WarnLog derives ActivityEncoder

final case class CallbackRejectedStateExpired(state: LinkState)
    extends WarnLog derives ActivityEncoder

final case class CallbackRejectedGitHubFailure(state: LinkState, message: String)
    extends WarnLog derives ActivityEncoder

final case class CallbackCompleted(
    state: LinkState,
    ghInstallationId: GhInstallationId,
    installationId: InstallationId,
    addedRepos: Int,
    removedRepos: Int,
    renamedRepos: Int
) extends InfoLog derives ActivityEncoder

final case class LinkAbandoned(state: LinkState) extends InfoLog derives ActivityEncoder

// ----- Read APIs -------------------------------------------------------------

final case class InstallationsListed(returned: Int, total: Long)
    extends InfoLog derives ActivityEncoder

final case class InstallationReposListed(
    ghInstallationId: GhInstallationId,
    returned: Int,
    total: Long
) extends InfoLog derives ActivityEncoder

final case class InstallationReposListRejectedNotFound(ghInstallationId: GhInstallationId)
    extends WarnLog derives ActivityEncoder

final case class ReposListed(returned: Int, total: Long)
    extends InfoLog derives ActivityEncoder

// ----- Unlink ----------------------------------------------------------------

final case class UnlinkRequested(ghInstallationId: GhInstallationId)
    extends InfoLog derives ActivityEncoder

final case class UnlinkCompleted(ghInstallationId: GhInstallationId)
    extends InfoLog derives ActivityEncoder

final case class UnlinkRejectedGitHubFailure(
    ghInstallationId: GhInstallationId,
    message: String
) extends WarnLog derives ActivityEncoder

// ----- Reconcile -------------------------------------------------------------

final case class ReconcileRequested(ghInstallationId: GhInstallationId)
    extends InfoLog derives ActivityEncoder

final case class ReconcileCompleted(
    ghInstallationId: GhInstallationId,
    added: Int,
    removed: Int,
    renamed: Int
) extends InfoLog derives ActivityEncoder

final case class ReconcileRejectedNotFound(ghInstallationId: GhInstallationId)
    extends WarnLog derives ActivityEncoder

final case class ReconcileRejectedGitHubFailure(
    ghInstallationId: GhInstallationId,
    message: String
) extends WarnLog derives ActivityEncoder

// ----- Sweep -----------------------------------------------------------------

final case class SweepCompleted(removed: Int) extends InfoLog derives ActivityEncoder

// ----- Webhooks --------------------------------------------------------------

final case class WebhookReceived(deliveryId: DeliveryId, eventType: EventType)
    extends InfoLog derives ActivityEncoder

final case class WebhookSignatureRejected(deliveryId: DeliveryId, eventType: EventType)
    extends WarnLog derives ActivityEncoder

final case class WebhookDeduplicated(deliveryId: DeliveryId, eventType: EventType)
    extends InfoLog derives ActivityEncoder

final case class WebhookProcessed(
    deliveryId: DeliveryId,
    eventType: EventType,
    variant: String
) extends InfoLog derives ActivityEncoder

final case class WebhookIgnored(
    deliveryId: DeliveryId,
    eventType: EventType,
    action: Option[EventAction]
) extends InfoLog derives ActivityEncoder

final case class WebhookFailed(
    deliveryId: DeliveryId,
    eventType: EventType,
    reason: String
) extends WarnLog derives ActivityEncoder
