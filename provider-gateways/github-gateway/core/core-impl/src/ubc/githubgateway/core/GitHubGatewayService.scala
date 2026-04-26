package ubc.githubgateway.core

import neotype.*
import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.core.ports.*
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Core service for the GitHub App installation lifecycle.
  *
  * Composes the eight outbound ports (four repos, one outbound HTTP client, one token minter,
  * one randomness source, one crypto adapter) into the operations exposed by the API layer.
  *
  * Scope:
  *   - link initiation (PKCE state + verifier, persist pending row, build install URL)
  *   - link callback (consume pending row, fetch installation + repos, persist mirror)
  *   - listing / unlinking / reconciling installations + their repo sets
  *   - sweeping expired pending flows
  *   - dispatching verified webhook deliveries onto the repository state
  *
  * No HTTP, no SQL, no JSON. Errors are translated at the GitHub-call boundary into the
  * domain `LinkError` / `UnlinkError` / `ReconcileError` / `WebhookError` enums.
  */
case class GitHubGatewayService(
    config: GitHubGatewayConfig,
    installations: InstallationRepository,
    linkedRepos: LinkedRepoRepository,
    pendingFlows: PendingLinkFlowRepository,
    webhookLog: WebhookDeliveryRepository,
    github: GitHubAppClient,
    minter: InstallationTokenMinter,
    random: SecureRandom,
    crypto: Crypto
):

  // ---------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------

  private def base64UrlNoPadding(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def sha256(input: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(input)

  private def asGitHubFailure(t: Throwable): LinkError.GitHubFailure =
    LinkError.GitHubFailure(Option(t.getMessage).getOrElse(t.getClass.getName))

  private def asUnlinkFailure(t: Throwable): UnlinkError.GitHubFailure =
    UnlinkError.GitHubFailure(Option(t.getMessage).getOrElse(t.getClass.getName))

  private def asReconcileFailure(t: Throwable): ReconcileError.GitHubFailure =
    ReconcileError.GitHubFailure(Option(t.getMessage).getOrElse(t.getClass.getName))

  private def hmacSha256Hex(secret: Array[Byte], body: Array[Byte]): String =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
    "sha256=" + mac.doFinal(body).map(b => f"$b%02x").mkString

  // ---------------------------------------------------------------------------------------
  // Link initiation
  // ---------------------------------------------------------------------------------------

  /** Begin a link flow. Generates a fresh state nonce + PKCE verifier, persists the pending
    * row, and returns the GitHub install URL the caller should redirect the user to.
    *
    * No modelled errors — entropy, encryption, and pending-row insertion are all infrastructure
    * defects rather than business outcomes. Surface them as ZIO defects (via `.orDie`).
    */
  def initiate(): UIO[LinkInitiation] =
    for
      state              <- random.newLinkState()
      verifier           <- random.newCodeVerifier()
      challenge           = CodeChallenge(
                              base64UrlNoPadding(sha256(verifier.unwrap.getBytes(StandardCharsets.UTF_8)))
                            )
      encryptedVerifier  <- crypto.encrypt(verifier.unwrap).orDie
      now                <- Clock.instant
      expiresAt           = now.plus(config.pendingLinkTtl)
      flow                = PendingLinkFlow(
                              state             = state,
                              encryptedVerifier = encryptedVerifier,
                              codeChallenge     = challenge,
                              createdAt         = now,
                              expiresAt         = expiresAt
                            )
      _                  <- pendingFlows.insert(flow).orDie
      installUrl          = InstallUrl(
                              s"https://github.com/apps/${config.appSlug.unwrap}/installations/new?state=${state.unwrap}"
                            )
    yield LinkInitiation(installUrl, state, expiresAt)

  // ---------------------------------------------------------------------------------------
  // Link callback
  // ---------------------------------------------------------------------------------------

  /** Complete a link flow.
    *
    * The pending row is single-use: we delete it on every exit path (success, expired, GitHub
    * failure) by bracketing the dispatch. The single exception is `StateNotFound` — there is
    * no row to delete in that case.
    */
  def callback(state: LinkState, ghInstallationId: GhInstallationId): IO[LinkError, Unit] =
    pendingFlows
      .findByState(state)
      .orDie
      .flatMap {
        case None       => ZIO.fail(LinkError.StateNotFound)
        case Some(flow) =>
          ZIO.acquireReleaseWith(ZIO.succeed(flow))(_ =>
            pendingFlows.deleteByState(state).ignore
          ) { f =>
            for
              now <- Clock.instant
              _   <- ZIO.fail(LinkError.StateExpired).when(!f.expiresAt.isAfter(now))
              jwt <- minter.mintAppJwt().mapError(asGitHubFailure)
              installation <- github.getInstallation(jwt, ghInstallationId).mapError(asGitHubFailure)
              persisted    <- installations
                                .upsertByGhInstallationId(
                                  ghInstallationId,
                                  installation.accountLogin,
                                  installation.accountId,
                                  installation.accountType,
                                  installation.status,
                                  installation.installedAt
                                )
                                .orDie
              token   <- github.createInstallationToken(jwt, ghInstallationId).mapError(asGitHubFailure)
              desired <- github.listInstallationRepos(token, ghInstallationId).mapError(asGitHubFailure)
              _       <- linkedRepos.replaceSet(persisted.id, desired).orDie
            yield ()
          }
      }

  /** Discard a pending flow without completing it. Idempotent — never raises. */
  def abandon(state: LinkState): UIO[Unit] =
    pendingFlows.deleteByState(state).ignore.unit

  // ---------------------------------------------------------------------------------------
  // Listing
  // ---------------------------------------------------------------------------------------

  def listInstallations(page: PageRequest): UIO[Page[Installation]] =
    installations.listAll(page).orDie

  def listInstallationRepos(
      ghInstallationId: GhInstallationId,
      page: PageRequest
  ): IO[ReconcileError, Page[LinkedRepo]] =
    installations.findByGhInstallationId(ghInstallationId).orDie.flatMap {
      case None        => ZIO.fail(ReconcileError.InstallationNotFound)
      case Some(inst)  => linkedRepos.listByInstallation(inst.id, page).orDie
    }

  def listRepos(page: PageRequest): UIO[Page[LinkedRepo]] =
    linkedRepos.listAll(page).orDie

  // ---------------------------------------------------------------------------------------
  // Unlink
  // ---------------------------------------------------------------------------------------

  def unlink(ghInstallationId: GhInstallationId): IO[UnlinkError, Unit] =
    for
      jwt    <- minter.mintAppJwt().mapError(asUnlinkFailure)
      result <- github.deleteInstallation(jwt, ghInstallationId).mapError(asUnlinkFailure)
      _      <- result match
                  case Left(message) => ZIO.fail(UnlinkError.GitHubFailure(message))
                  case Right(())     => installations.deleteByGhInstallationId(ghInstallationId).orDie.unit
    yield ()

  // ---------------------------------------------------------------------------------------
  // Reconcile
  // ---------------------------------------------------------------------------------------

  def reconcile(ghInstallationId: GhInstallationId): IO[ReconcileError, ReconcileSummary] =
    installations.findByGhInstallationId(ghInstallationId).orDie.flatMap {
      case None           => ZIO.fail(ReconcileError.InstallationNotFound)
      case Some(existing) =>
        for
          jwt          <- minter.mintAppJwt().mapError(asReconcileFailure)
          installation <- github.getInstallation(jwt, ghInstallationId).mapError(asReconcileFailure)
          _            <- installations
                            .upsertByGhInstallationId(
                              ghInstallationId,
                              installation.accountLogin,
                              installation.accountId,
                              installation.accountType,
                              installation.status,
                              installation.installedAt
                            )
                            .orDie
          token   <- github.createInstallationToken(jwt, ghInstallationId).mapError(asReconcileFailure)
          desired <- github.listInstallationRepos(token, ghInstallationId).mapError(asReconcileFailure)
          summary <- linkedRepos.replaceSet(existing.id, desired).orDie
        yield summary
    }

  // ---------------------------------------------------------------------------------------
  // Sweep
  // ---------------------------------------------------------------------------------------

  def sweepExpiredFlows(): UIO[Int] =
    for
      now   <- Clock.instant
      count <- pendingFlows.deleteExpired(now).orDie
    yield count

  // ---------------------------------------------------------------------------------------
  // Webhooks
  // ---------------------------------------------------------------------------------------

  def handleWebhook(
      headers: WebhookHeaders,
      rawBody: Array[Byte],
      event: GithubWebhookEvent
  ): IO[WebhookError, WebhookOutcome] =
    val expectedSignature = hmacSha256Hex(config.webhookSecret, rawBody)
    val sigOk = MessageDigest.isEqual(
      expectedSignature.getBytes(StandardCharsets.UTF_8),
      headers.signature.getBytes(StandardCharsets.UTF_8)
    )
    if !sigOk then ZIO.fail(WebhookError.InvalidSignature)
    else
      for
        now <- Clock.instant
        provisional = WebhookDelivery(
          deliveryId = headers.deliveryId,
          eventType  = headers.eventType,
          receivedAt = now,
          outcome    = WebhookOutcome.Processed
        )
        inserted <- webhookLog.recordIfAbsent(provisional).orDie
        outcome  <- if !inserted then ZIO.succeed(WebhookOutcome.Duplicate)
                    else dispatch(event)
        _ <- webhookLog.updateOutcome(headers.deliveryId, outcome).orDie
      yield outcome

  private def dispatch(event: GithubWebhookEvent): UIO[WebhookOutcome] =
    event match
      case GithubWebhookEvent.InstallationDeleted(id) =>
        installations.deleteByGhInstallationId(id).orDie.as(WebhookOutcome.Processed)

      case GithubWebhookEvent.InstallationSuspended(id) =>
        installations.updateStatus(id, InstallationStatus.Suspended).orDie.as(WebhookOutcome.Processed)

      case GithubWebhookEvent.InstallationUnsuspended(id) =>
        installations.updateStatus(id, InstallationStatus.Active).orDie.as(WebhookOutcome.Processed)

      case GithubWebhookEvent.RepositoriesAdded(ghId, added) =>
        installations.findByGhInstallationId(ghId).orDie.flatMap {
          case None       => ZIO.succeed(WebhookOutcome.Failed)
          case Some(inst) => linkedRepos.insertMany(inst.id, added).orDie.as(WebhookOutcome.Processed)
        }

      case GithubWebhookEvent.RepositoriesRemoved(ghId, removed) =>
        installations.findByGhInstallationId(ghId).orDie.flatMap {
          case None       => ZIO.succeed(WebhookOutcome.Failed)
          case Some(inst) =>
            linkedRepos.deleteByGhRepositoryIds(inst.id, removed).orDie.as(WebhookOutcome.Processed)
        }

      case GithubWebhookEvent.RepositoryRenamed(ghRepoId, newName) =>
        linkedRepos.renameByGhRepositoryId(ghRepoId, newName).orDie.as(WebhookOutcome.Processed)

      case GithubWebhookEvent.RepositoryTransferred(ghRepoId, newName) =>
        linkedRepos.renameByGhRepositoryId(ghRepoId, newName).orDie.as(WebhookOutcome.Processed)

      case GithubWebhookEvent.Unhandled(_, _) =>
        ZIO.succeed(WebhookOutcome.Ignored)

object GitHubGatewayService:
  val layer: URLayer[
    GitHubGatewayConfig &
      InstallationRepository & LinkedRepoRepository &
      PendingLinkFlowRepository & WebhookDeliveryRepository &
      GitHubAppClient & InstallationTokenMinter &
      SecureRandom & Crypto,
    GitHubGatewayService
  ] =
    ZLayer.fromFunction(GitHubGatewayService.apply)
