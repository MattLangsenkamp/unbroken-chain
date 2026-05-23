package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.DeliveryId
import ubc.githubgateway.domain.internal.{WebhookDelivery, WebhookOutcome}
import zio.Task

/** Persistence port for the webhook idempotency log.
  *
  * GitHub's `X-GitHub-Delivery` UUID is the idempotency key — seeing the same id twice means the delivery was already
  * accepted and processed (or recorded as failed). The log also serves as the audit trail for after-the-fact debugging.
  */
trait WebhookDeliveryRepository:

  /** Atomically record a delivery iff one with the same `deliveryId` is not already present.
    *
    * Adapters MUST implement this as a single transactional `INSERT … ON CONFLICT DO NOTHING` (or equivalent). Two-step
    * "check then insert" is not acceptable — duplicate deliveries can arrive concurrently.
    *
    * @return
    *   `true` if a new row was inserted, `false` if a row with the same deliveryId existed.
    */
  def recordIfAbsent(delivery: WebhookDelivery): Task[Boolean]

  /** Update the recorded `outcome` for an existing delivery row.
    *
    * Used after dispatch resolves the final outcome (Processed / Ignored / Failed). No-op if no row matches the given
    * `deliveryId` — callers should always have called [[recordIfAbsent]] first.
    *
    * Deviation from Layer 2: this method was added in Layer 3 to support recording the post-dispatch outcome. The Layer
    * 2 port shape only exposed `recordIfAbsent`, which seeded the row with a provisional `Processed` outcome.
    */
  def updateOutcome(deliveryId: DeliveryId, outcome: WebhookOutcome): Task[Unit]
