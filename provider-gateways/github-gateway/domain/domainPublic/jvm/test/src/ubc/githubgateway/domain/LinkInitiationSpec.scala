package ubc.githubgateway.domain

import neotype.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object LinkInitiationSpec extends ZIOSpecDefault:

  private val state = LinkState(UUID.fromString("00000000-0000-0000-0000-0000000000ab"))

  override def spec =
    suite("LinkInitiationSpec")(
      test("LinkInitiation carries the install URL, server-generated state, and an expiry") {
        val expiresAt = Instant.parse("2026-04-25T13:00:00Z")
        val init      = LinkInitiation(
          installUrl = InstallUrl(s"https://github.com/apps/my-app/installations/new?state=${state.unwrap}"),
          state = state,
          expiresAt = expiresAt
        )
        assertTrue(
          init.installUrl == InstallUrl(s"https://github.com/apps/my-app/installations/new?state=${state.unwrap}"),
          init.state == state,
          init.expiresAt == expiresAt
        )
      },
      test("ReconcileSummary tracks added, removed, and renamed counts") {
        val summary = ReconcileSummary(added = 3, removed = 1, renamed = 2)
        assertTrue(
          summary.added == 3,
          summary.removed == 1,
          summary.renamed == 2
        )
      },
      test("ReconcileSummary supports a no-op summary") {
        val summary = ReconcileSummary(added = 0, removed = 0, renamed = 0)
        assertTrue(summary == ReconcileSummary(0, 0, 0))
      }
    )
