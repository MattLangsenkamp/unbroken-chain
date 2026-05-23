package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.WebhookDeliveryRepository
import ubc.githubgateway.domain.DeliveryId
import ubc.githubgateway.domain.internal.{WebhookDelivery, WebhookOutcome}
import zio.*

/** Ref-backed [[WebhookDeliveryRepository]] for tests and local dev.
  *
  * State is keyed by [[DeliveryId]] — Postgres enforces the same uniqueness via a primary key on `delivery_id`.
  * `Ref.modify` gives the atomic check-and-set semantics that the port contract requires.
  */
final class InMemoryWebhookDeliveryRepository(
    store: Ref[Map[DeliveryId, WebhookDelivery]]
) extends WebhookDeliveryRepository:

  def recordIfAbsent(delivery: WebhookDelivery): Task[Boolean] =
    store.modify { current =>
      if current.contains(delivery.deliveryId) then (false, current)
      else (true, current.updated(delivery.deliveryId, delivery))
    }

  def updateOutcome(deliveryId: DeliveryId, outcome: WebhookOutcome): Task[Unit] =
    store.update { current =>
      current.get(deliveryId) match
        case Some(existing) => current.updated(deliveryId, existing.copy(outcome = outcome))
        case None           => current
    }

  // Test-only accessor: read the persisted delivery row.
  def peek(deliveryId: DeliveryId): UIO[Option[WebhookDelivery]] =
    store.get.map(_.get(deliveryId))

object InMemoryWebhookDeliveryRepository:
  /** Layer that provides BOTH the port-typed binding and the concrete-typed binding so tests can call the test-only
    * `peek` / seeding helpers.
    */
  val layer: ZLayer[Any, Nothing, WebhookDeliveryRepository & InMemoryWebhookDeliveryRepository] =
    ZLayer
      .fromZIO(
        Ref.make(Map.empty[DeliveryId, WebhookDelivery]).map(new InMemoryWebhookDeliveryRepository(_))
      )
      .flatMap { env =>
        val fake = env.get[InMemoryWebhookDeliveryRepository]
        ZLayer.succeedEnvironment(
          ZEnvironment[WebhookDeliveryRepository, InMemoryWebhookDeliveryRepository](fake, fake)
        )
      }
