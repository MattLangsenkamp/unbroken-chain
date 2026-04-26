package ubc.githubgateway.core.adapters.magnum

import ubc.githubgateway.core.ports.WebhookDeliveryRepository
import ubc.githubgateway.domain.DeliveryId
import ubc.githubgateway.domain.internal.{WebhookDelivery, WebhookOutcome}
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.githubgateway.domain.internal.adapters.magnum.PrivateMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*

private given DbCodec[WebhookDelivery] = DbCodec.derived[WebhookDelivery]

class MagnumWebhookDeliveryRepository(xa: TransactorZIO) extends WebhookDeliveryRepository:

  def recordIfAbsent(delivery: WebhookDelivery): Task[Boolean] =
    xa.transact {
      sql"""
        INSERT INTO webhook_delivery (delivery_id, event_type, received_at, outcome)
        VALUES (${delivery.deliveryId}, ${delivery.eventType}, ${delivery.receivedAt}, ${delivery.outcome})
        ON CONFLICT (delivery_id) DO NOTHING
      """.update.run()
    }.map(_ > 0)

  def updateOutcome(deliveryId: DeliveryId, outcome: WebhookOutcome): Task[Unit] =
    xa.transact {
      sql"UPDATE webhook_delivery SET outcome = $outcome WHERE delivery_id = $deliveryId".update.run()
    }.unit

object MagnumWebhookDeliveryRepository:
  val layer: ZLayer[TransactorZIO, Nothing, WebhookDeliveryRepository] =
    ZLayer.fromFunction(new MagnumWebhookDeliveryRepository(_))
