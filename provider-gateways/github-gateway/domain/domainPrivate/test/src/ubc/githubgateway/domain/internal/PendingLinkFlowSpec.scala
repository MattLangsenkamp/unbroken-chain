package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.LinkState
import zio.test.*

import java.time.Instant
import java.util.UUID

object PendingLinkFlowSpec extends ZIOSpecDefault:

  private val state = LinkState(UUID.fromString("00000000-0000-0000-0000-000000000123"))

  override def spec =
    suite("PendingLinkFlowSpec")(
      test("PendingLinkFlow holds state and timestamps") {
        val createdAt = Instant.parse("2026-04-25T12:00:00Z")
        val expiresAt = Instant.parse("2026-04-25T12:10:00Z")
        val flow = PendingLinkFlow(
          state     = state,
          createdAt = createdAt,
          expiresAt = expiresAt
        )
        assertTrue(
          flow.state == state,
          flow.createdAt == createdAt,
          flow.expiresAt == expiresAt
        )
      }
    )
