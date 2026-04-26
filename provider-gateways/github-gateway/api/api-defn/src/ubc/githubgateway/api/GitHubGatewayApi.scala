package ubc.githubgateway.api

import ubc.common.pagination.Page
import ubc.githubgateway.domain.*
import zio.Task

/** Semantic contract for the GitHub Gateway service.
  *
  * Cross-compiled (JVM + Scala.js) so the Tyrian SPA consumes the same trait the JVM
  * controller serves. Domain types are used directly in signatures — clients and the server
  * both consume this trait.
  *
  * Errors are surfaced as `Task` failures using the [[ApiError]] envelope. Domain error
  * variants from `domainPrivate` (LinkError, UnlinkError, ReconcileError, WebhookError) are
  * mapped to ApiError at the server boundary; they never cross the API.
  *
  * Webhook ingestion (POST /webhooks/github) is intentionally NOT on this trait — webhooks
  * come from GitHub directly to the server route, never from a typed client.
  */
trait GitHubGatewayApi:

  // -- Linking flow --

  /** Begin a fresh link flow. Returns an install URL to redirect the user to plus the
    * server-generated state nonce we will validate when GitHub calls our callback.
    */
  def initiate(): Task[LinkInitiation]

  /** Complete a link flow by exchanging the state nonce for a finalized installation +
    * mirrored repo set. GitHub redirects the user here after install; the typed client
    * variant exists mainly so the SPA can drive the callback path during tests.
    */
  def callback(state: LinkState, ghInstallationId: GhInstallationId): Task[Unit]

  /** Discard a pending flow without completing it. Idempotent. */
  def abandon(state: LinkState): Task[Unit]

  // -- Read APIs --

  def listInstallations(cursor: Option[String], limit: Int): Task[Page[Installation]]

  def listInstallationRepos(
      ghInstallationId: GhInstallationId,
      cursor: Option[String],
      limit: Int
  ): Task[Page[LinkedRepo]]

  def listRepos(cursor: Option[String], limit: Int): Task[Page[LinkedRepo]]

  // -- Mutations --

  def unlink(ghInstallationId: GhInstallationId): Task[Unit]

  def reconcile(ghInstallationId: GhInstallationId): Task[ReconcileSummary]

  // -- Health --

  def health(): Task[Unit]
