package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.{AppId, AppSlug}
import zio.*
import zio.config.*
import zio.config.magnolia.deriveConfig

/** Bundled configuration for the github-gateway server.
  *
  * One config to rule them all: held in `domainPrivate` because every server module pulls config in anyway, so
  * isolating it from that classpath buys nothing (per the `feature-tdd/layers/domain.md` skill).
  *
  * Sourced from environment variables at startup via `ZIO.config[GitHubGatewayConfig]`. Each companion-object layer
  * (`NimbusJoseInstallationTokenMinter.layer`, `GitHubGatewayService.sweeperLayer`, …) reads what it needs from this
  * single value.
  *
  * @param githubAppId
  *   GitHub App's numeric id (echoed in webhook payloads — used by the JWT minter to populate the `iss` claim)
  * @param githubAppSlug
  *   GitHub App's URL slug — used to build the install URL in `service.initiate()`
  * @param githubAppPrivateKeyPem
  *   PEM-encoded RSA private key (PKCS#8) used to sign the App-level JWT. Sourced from a Kubernetes secret; never
  *   persisted, never logged.
  * @param githubWebhookSecret
  *   raw bytes of the webhook signing secret (sourced as a UTF-8 string from a secret) — used to verify HMAC-SHA-256
  *   signatures on inbound webhooks
  * @param pendingLinkTtl
  *   how long a `pending_link_flow` row remains valid before sweep
  * @param linkSuccessUrl
  *   absolute URL the gateway 302s to after a successful link callback
  * @param linkFailureUrl
  *   absolute URL the gateway 302s to when the link callback fails (`?error=<code>` is appended by the controller so
  *   the SPA can render a useful message)
  * @param appJwtTtl
  *   lifetime of the App-level JWT used to mint installation tokens (capped at 10 minutes by GitHub; default ~9 minutes
  *   leaves comfortable headroom)
  * @param sweepInterval
  *   how often the background sweeper drains expired `pending_link_flow` rows
  */
final case class GitHubGatewayConfig(
    githubAppId: AppId,
    githubAppSlug: AppSlug,
    githubAppPrivateKeyPem: String,
    githubWebhookSecret: String,
    pendingLinkTtl: Duration,
    linkSuccessUrl: String,
    linkFailureUrl: String,
    appJwtTtl: Duration,
    sweepInterval: Duration
)

object GitHubGatewayConfig:

  // Magnum-flavoured `derives Config` doesn't reach into neotype newtypes, so we lift the
  // primitive-typed read into the typed shape via .map. Keeping this `private` keeps the
  // raw-string config invisible outside the companion.
  private final case class Raw(
      githubAppId: Long,
      githubAppSlug: String,
      githubAppPrivateKeyPem: String,
      githubWebhookSecret: String,
      pendingLinkTtl: Duration,
      linkSuccessUrl: String,
      linkFailureUrl: String,
      appJwtTtl: Duration,
      sweepInterval: Duration
  )

  given Config[GitHubGatewayConfig] =
    deriveConfig[Raw].map { r =>
      GitHubGatewayConfig(
        githubAppId = AppId(r.githubAppId),
        githubAppSlug = AppSlug(r.githubAppSlug),
        githubAppPrivateKeyPem = r.githubAppPrivateKeyPem,
        githubWebhookSecret = r.githubWebhookSecret,
        pendingLinkTtl = r.pendingLinkTtl,
        linkSuccessUrl = r.linkSuccessUrl,
        linkFailureUrl = r.linkFailureUrl,
        appJwtTtl = r.appJwtTtl,
        sweepInterval = r.sweepInterval
      )
    }

  val layer: TaskLayer[GitHubGatewayConfig] =
    ZLayer.fromZIO(ZIO.config[GitHubGatewayConfig])
