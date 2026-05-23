package ubc.githubgateway.domain.internal

import zio.test.*

object InternalDomainGeneratorsSpec extends ZIOSpecDefault:

  import InternalDomainGenerators.*

  override def spec =
    suite("InternalDomainGeneratorsSpec")(
      test("Sensitive token newtypes round-trip through their smart constructor") {
        check(appJwt, installationAccessToken) { (j, t) =>
          assertTrue(
            AppJwt.sensitive(AppJwt.unwrap(j)) == j,
            InstallationAccessToken.sensitive(InstallationAccessToken.unwrap(t)) == t
          )
        }
      },
      test("WebhookOutcome generator only produces defined cases") {
        check(webhookOutcome) { o =>
          assertTrue(WebhookOutcome.values.contains(o))
        }
      },
      test("parameterised error enums sample constructible values") {
        check(linkError, unlinkError, reconcileError, webhookError) { (l, u, r, w) =>
          assertTrue(
            l.toString.nonEmpty,
            u.toString.nonEmpty,
            r.toString.nonEmpty,
            w.toString.nonEmpty
          )
        }
      },
      test("GithubWebhookEvent generator samples constructible values") {
        check(githubWebhookEvent) { e =>
          assertTrue(e.toString.nonEmpty)
        }
      },
      test("WebhookDelivery generator only produces defined outcomes (and headers/flow sample)") {
        check(webhookHeaders, pendingLinkFlow, webhookDelivery) { (_, _, d) =>
          assertTrue(WebhookOutcome.values.contains(d.outcome))
        }
      },
      test("GitHubGatewayConfig generator populates duration fields") {
        check(gitHubGatewayConfig) { c =>
          assertTrue(c.pendingLinkTtl == c.pendingLinkTtl, c.appJwtTtl == c.appJwtTtl)
        }
      }
    )
