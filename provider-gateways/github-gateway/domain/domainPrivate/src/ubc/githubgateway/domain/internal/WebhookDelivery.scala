package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.{DeliveryId, EventType}

import java.time.Instant

/** How a single webhook delivery was handled.
  *
  *   - [[Processed]] — verified, parsed, and the resulting state change was applied.
  *   - [[Ignored]] — accepted but intentionally not acted on (e.g. unknown event type).
  *   - [[Duplicate]] — same `deliveryId` was already recorded; second receipt was a no-op.
  *   - [[Failed]] — accepted (signature OK) but processing threw; will be retried via
  *     GitHub's redelivery mechanism.
  */
enum WebhookOutcome:
  case Processed
  case Ignored
  case Duplicate
  case Failed

/** A single webhook delivery record — used both for replay protection (via [[deliveryId]])
  * and for after-the-fact debugging.
  *
  * @param deliveryId
  *   value of `X-GitHub-Delivery` (UUID)
  * @param eventType
  *   value of `X-GitHub-Event` (e.g. `installation`, `installation_repositories`)
  * @param receivedAt
  *   when our server accepted the request
  * @param outcome
  *   how processing ended
  */
final case class WebhookDelivery(
    deliveryId: DeliveryId,
    eventType: EventType,
    receivedAt: Instant,
    outcome: WebhookOutcome
)
