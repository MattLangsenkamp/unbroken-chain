package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.internal.WebhookDelivery
import zio.Task

/** Persistence port for the webhook idempotency log.
  *
  * GitHub's `X-GitHub-Delivery` UUID is the idempotency key — seeing the same id twice
  * means the delivery was already accepted and processed (or recorded as failed). The log
  * also serves as the audit trail for after-the-fact debugging.
  */
trait WebhookDeliveryRepository:

  /** Atomically record a delivery iff one with the same `deliveryId` is not already present.
    *
    * Adapters MUST implement this as a single transactional `INSERT … ON CONFLICT DO NOTHING`
    * (or equivalent). Two-step "check then insert" is not acceptable — duplicate deliveries can
    * arrive concurrently.
    *
    * @return `true` if a new row was inserted, `false` if a row with the same deliveryId existed.
    */
  def recordIfAbsent(delivery: WebhookDelivery): Task[Boolean]
