package ubc.githubgateway.core

import ubc.githubgateway.domain.{AppId, AppSlug}
import zio.Duration

/** Bundled configuration for the core service.
  *
  * Supplied by the server's bootstrap from environment variables / k8s secrets — none of these
  * values are sensitive in themselves (the App private key and the Crypto key live on adapters,
  * not here).
  *
  * @param appId
  *   GitHub App's numeric id (echoed in some webhook payloads — not currently used in core
  *   logic, but kept here so future operations can fail-fast on App id mismatch)
  * @param appSlug
  *   GitHub App's URL slug — used to build the install URL returned from
  *   [[GitHubGatewayService.initiate]]
  * @param pendingLinkTtl
  *   how long a pending link flow row remains valid before sweep
  * @param webhookSecret
  *   raw bytes of the webhook signing secret — used by the service to verify HMAC-SHA-256
  *   signatures on inbound webhooks. `Array[Byte]` (not a domain newtype) because it's
  *   constructor-only — never persisted, never logged.
  * @param successRedirectUrl
  *   absolute URL the driving adapter redirects the user to after a successful link callback
  * @param failureRedirectUrl
  *   absolute URL the driving adapter redirects the user to when the link callback fails
  *   (state-not-found, expired, GitHub failure). The driving adapter appends an `?error=<code>`
  *   query parameter so the SPA can render a useful message.
  */
final case class GitHubGatewayConfig(
    appId: AppId,
    appSlug: AppSlug,
    pendingLinkTtl: Duration,
    webhookSecret: Array[Byte],
    successRedirectUrl: String,
    failureRedirectUrl: String
)
