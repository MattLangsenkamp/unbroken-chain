package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.WebhookDeliveryRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object InMemoryWebhookDeliveryRepositorySpec extends ZIOSpecDefault:

  // DeliveryId is a UUID; map a readable label to a stable UUID so test keys stay legible.
  private def did(label: String): DeliveryId = DeliveryId(UUID.nameUUIDFromBytes(label.getBytes))

  override def spec =
    suite("InMemoryWebhookDeliveryRepository")(
      test("recordIfAbsent returns true on first call, false on duplicate deliveryId") {
        for
          repo <- ZIO.service[WebhookDeliveryRepository]
          delivery = WebhookDelivery(
            deliveryId = did("uuid-1"),
            eventType = EventType("installation"),
            receivedAt = Instant.parse("2026-04-25T12:00:00Z"),
            outcome = WebhookOutcome.Processed
          )
          first  <- repo.recordIfAbsent(delivery)
          second <- repo.recordIfAbsent(delivery)
          // even with a different outcome, same deliveryId is still a duplicate
          third <- repo.recordIfAbsent(delivery.copy(outcome = WebhookOutcome.Failed))
        yield assertTrue(first, !second, !third)
      },
      test("recordIfAbsent treats different deliveryIds as distinct rows") {
        for
          repo <- ZIO.service[WebhookDeliveryRepository]
          a = WebhookDelivery(
            did("uuid-A"),
            EventType("installation"),
            Instant.parse("2026-04-25T12:00:00Z"),
            WebhookOutcome.Processed
          )
          b = WebhookDelivery(
            did("uuid-B"),
            EventType("installation"),
            Instant.parse("2026-04-25T12:00:00Z"),
            WebhookOutcome.Processed
          )
          ra <- repo.recordIfAbsent(a)
          rb <- repo.recordIfAbsent(b)
        yield assertTrue(ra, rb)
      },
      test("updateOutcome rewrites the outcome of an existing row") {
        for
          repo <- ZIO.service[WebhookDeliveryRepository]
          fake <- ZIO.service[InMemoryWebhookDeliveryRepository]
          delivery = WebhookDelivery(
            deliveryId = did("uuid-update"),
            eventType = EventType("installation"),
            receivedAt = Instant.parse("2026-04-25T12:00:00Z"),
            outcome = WebhookOutcome.Processed
          )
          _      <- repo.recordIfAbsent(delivery)
          _      <- repo.updateOutcome(did("uuid-update"), WebhookOutcome.Failed)
          stored <- fake.peek(did("uuid-update"))
        yield assertTrue(stored.exists(_.outcome == WebhookOutcome.Failed))
      },
      test("updateOutcome is a no-op when no matching row exists") {
        for
          repo   <- ZIO.service[WebhookDeliveryRepository]
          fake   <- ZIO.service[InMemoryWebhookDeliveryRepository]
          _      <- repo.updateOutcome(did("uuid-missing"), WebhookOutcome.Failed)
          stored <- fake.peek(did("uuid-missing"))
        yield assertTrue(stored.isEmpty)
      }
    ).provide(InMemoryWebhookDeliveryRepository.layer)
