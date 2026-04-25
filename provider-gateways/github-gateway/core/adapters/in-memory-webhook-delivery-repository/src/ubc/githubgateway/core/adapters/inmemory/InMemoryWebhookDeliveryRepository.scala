package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.WebhookDeliveryRepository
import ubc.githubgateway.domain.DeliveryId
import ubc.githubgateway.domain.internal.WebhookDelivery
import zio.*

/** Ref-backed [[WebhookDeliveryRepository]] for tests and local dev.
  *
  * State is keyed by [[DeliveryId]] — Postgres enforces the same uniqueness via a primary key
  * on `delivery_id`. `Ref.modify` gives the atomic check-and-set semantics that the port
  * contract requires.
  */
final class InMemoryWebhookDeliveryRepository(
    store: Ref[Map[DeliveryId, WebhookDelivery]]
) extends WebhookDeliveryRepository:

  def recordIfAbsent(delivery: WebhookDelivery): Task[Boolean] =
    store.modify { current =>
      if current.contains(delivery.deliveryId) then (false, current)
      else (true, current.updated(delivery.deliveryId, delivery))
    }

object InMemoryWebhookDeliveryRepository:
  val layer: ULayer[WebhookDeliveryRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[DeliveryId, WebhookDelivery]).map(new InMemoryWebhookDeliveryRepository(_))
    )
