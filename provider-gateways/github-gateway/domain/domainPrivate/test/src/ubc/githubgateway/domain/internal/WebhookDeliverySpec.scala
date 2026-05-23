package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.{DeliveryId, EventType}
import zio.test.*

import java.time.Instant
import java.util.UUID

object WebhookDeliverySpec extends ZIOSpecDefault:

  override def spec =
    suite("WebhookDeliverySpec")(
      test("WebhookOutcome has Processed, Ignored, Duplicate, and Failed cases") {
        val cases: List[WebhookOutcome] = List(
          WebhookOutcome.Processed,
          WebhookOutcome.Ignored,
          WebhookOutcome.Duplicate,
          WebhookOutcome.Failed
        )
        assertTrue(cases.distinct.size == 4)
      },
      test("WebhookOutcome pattern-matches exhaustively") {
        def label(o: WebhookOutcome): String = o match
          case WebhookOutcome.Processed => "processed"
          case WebhookOutcome.Ignored   => "ignored"
          case WebhookOutcome.Duplicate => "duplicate"
          case WebhookOutcome.Failed    => "failed"
        assertTrue(
          label(WebhookOutcome.Processed) == "processed",
          label(WebhookOutcome.Ignored) == "ignored",
          label(WebhookOutcome.Duplicate) == "duplicate",
          label(WebhookOutcome.Failed) == "failed"
        )
      },
      test("WebhookDelivery records id, event, receipt time, and outcome") {
        val receivedAt = Instant.parse("2026-04-25T12:00:00Z")
        val deliveryId = DeliveryId(UUID.fromString("72d3162e-cc78-11e3-81ab-4c9367dc0958"))
        val delivery = WebhookDelivery(
          deliveryId = deliveryId,
          eventType  = EventType("installation"),
          receivedAt = receivedAt,
          outcome    = WebhookOutcome.Processed
        )
        assertTrue(
          delivery.deliveryId == deliveryId,
          delivery.eventType == EventType("installation"),
          delivery.receivedAt == receivedAt,
          delivery.outcome == WebhookOutcome.Processed
        )
      }
    )
