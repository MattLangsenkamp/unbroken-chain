package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.LinkState
import zio.test.*

import java.time.Instant

object PendingLinkFlowSpec extends ZIOSpecDefault:

  override def spec =
    suite("PendingLinkFlowSpec")(
      test("PendingLinkFlow holds state and timestamps") {
        val createdAt = Instant.parse("2026-04-25T12:00:00Z")
        val expiresAt = Instant.parse("2026-04-25T12:10:00Z")
        val flow = PendingLinkFlow(
          state     = LinkState("nonce-123"),
          createdAt = createdAt,
          expiresAt = expiresAt
        )
        assertTrue(
          flow.state == LinkState("nonce-123"),
          flow.createdAt == createdAt,
          flow.expiresAt == expiresAt
        )
      }
    )
