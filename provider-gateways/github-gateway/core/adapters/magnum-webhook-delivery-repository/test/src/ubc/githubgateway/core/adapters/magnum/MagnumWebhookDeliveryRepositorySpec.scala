package ubc.githubgateway.core.adapters.magnum

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import ubc.common.TestDatabase
import ubc.githubgateway.core.ports.WebhookDeliveryRepository
import ubc.githubgateway.domain.{DeliveryId, EventType}
import ubc.githubgateway.domain.internal.{WebhookDelivery, WebhookOutcome}
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.githubgateway.domain.internal.adapters.magnum.PrivateMagnumCodecs.given
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

/** Integration tests for [[MagnumWebhookDeliveryRepository]] against a real PostgreSQL.
  *
  * `recordIfAbsent` exercises Postgres' INSERT ... ON CONFLICT DO NOTHING semantics.
  */
object MagnumWebhookDeliveryRepositorySpec extends ZIOSpecDefault:

  private val migrationLocation = "classpath:db/migration"

  // DeliveryId is a UUID; map a readable label to a stable UUID so test keys stay legible.
  private def did(label: String): DeliveryId = DeliveryId(UUID.nameUUIDFromBytes(label.getBytes))

  // Need to derive the codec for direct SELECTs in test assertions.
  private given DbCodec[WebhookDelivery] = DbCodec.derived[WebhookDelivery]

  private val truncate: URIO[TransactorZIO, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](
      _.transact { sql"TRUNCATE TABLE webhook_delivery".update.run() }
    ).unit.orDie

  private def peek(deliveryId: DeliveryId): URIO[TransactorZIO, Option[WebhookDelivery]] =
    ZIO.serviceWithZIO[TransactorZIO](_.connect {
      sql"""SELECT delivery_id, event_type, received_at, outcome
            FROM webhook_delivery WHERE delivery_id = $deliveryId"""
        .query[WebhookDelivery].run().headOption
    }).orDie

  private val delivery1 = WebhookDelivery(
    deliveryId = did("uuid-1"),
    eventType  = EventType("installation"),
    receivedAt = Instant.parse("2025-01-01T00:00:00Z"),
    outcome    = WebhookOutcome.Processed
  )

  override def spec =
    (suite("MagnumWebhookDeliveryRepositorySpec")(

      test("recordIfAbsent first call returns true and inserts the row") {
        for
          repo     <- ZIO.service[WebhookDeliveryRepository]
          inserted <- repo.recordIfAbsent(delivery1)
          row      <- peek(delivery1.deliveryId)
        yield assertTrue(
          inserted,
          row.isDefined,
          row.get == delivery1
        )
      },

      test("recordIfAbsent second call with same id returns false; original row unchanged") {
        val collision = delivery1.copy(
          eventType  = EventType("installation_repositories"),
          receivedAt = Instant.parse("2099-01-01T00:00:00Z"),
          outcome    = WebhookOutcome.Failed
        )
        for
          repo     <- ZIO.service[WebhookDeliveryRepository]
          first    <- repo.recordIfAbsent(delivery1)
          second   <- repo.recordIfAbsent(collision)
          row      <- peek(delivery1.deliveryId)
        yield assertTrue(
          first,
          !second,
          row.get == delivery1 // ON CONFLICT DO NOTHING: original kept
        )
      },

      test("updateOutcome rewrites the outcome of an existing row") {
        for
          repo  <- ZIO.service[WebhookDeliveryRepository]
          _     <- repo.recordIfAbsent(delivery1)
          _     <- repo.updateOutcome(delivery1.deliveryId, WebhookOutcome.Failed)
          row   <- peek(delivery1.deliveryId)
        yield assertTrue(row.get.outcome == WebhookOutcome.Failed)
      },

      test("updateOutcome is a no-op when no row matches") {
        for
          repo <- ZIO.service[WebhookDeliveryRepository]
          _    <- repo.updateOutcome(did("missing"), WebhookOutcome.Failed)
          row  <- peek(did("missing"))
        yield assertTrue(row.isEmpty)
      }

    ) @@ TestAspect.before(truncate) @@ TestAspect.sequential)
      .provideShared(
        TestDatabase.suiteLayer(migrationLocation),
        TestDatabase.transactorLayer,
        MagnumWebhookDeliveryRepository.layer
      )
