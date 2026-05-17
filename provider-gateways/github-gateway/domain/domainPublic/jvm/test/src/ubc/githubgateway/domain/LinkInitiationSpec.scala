package ubc.githubgateway.domain

import zio.test.*

import java.time.Instant

object LinkInitiationSpec extends ZIOSpecDefault:

  override def spec =
    suite("LinkInitiationSpec")(
      test("LinkInitiation carries the install URL, server-generated state, and an expiry") {
        val expiresAt = Instant.parse("2026-04-25T13:00:00Z")
        val init = LinkInitiation(
          installUrl = InstallUrl("https://github.com/apps/my-app/installations/new?state=abc"),
          state      = LinkState("abc"),
          expiresAt  = expiresAt
        )
        assertTrue(
          init.installUrl == InstallUrl("https://github.com/apps/my-app/installations/new?state=abc"),
          init.state == LinkState("abc"),
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
