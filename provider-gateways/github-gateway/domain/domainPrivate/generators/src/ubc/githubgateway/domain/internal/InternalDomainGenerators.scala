package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.DomainGenerators.given
import zio.Duration
import zio.test.Gen
import zio.test.magnolia.DeriveGen

/** ZIO Test generators for the private github-gateway domain. Test-classpath only.
  *
  * Private types reference public newtypes, so this imports the public leaf `given`s from
  * [[ubc.githubgateway.domain.DomainGenerators]] to let derivation resolve them.
  */
object InternalDomainGenerators:

  // --- Leaf newtypes ---------------------------------------------------------------------

  // AppJwt / InstallationAccessToken are `Sensitive`; build through the smart constructor so
  // the marker survives and ActivityEncoder redacts generated values (see the sensitive skill).
  given DeriveGen[AppJwt]                  = DeriveGen.instance(Gen.string.map(AppJwt.sensitive))
  given DeriveGen[InstallationAccessToken] =
    DeriveGen.instance(Gen.string.map(InstallationAccessToken.sensitive))

  // zio.Duration has no built-in DeriveGen — provide one so config/flow types derive.
  given DeriveGen[Duration] = DeriveGen.instance(Gen.finiteDuration)

  val appJwt: Gen[Any, AppJwt]                                   = DeriveGen[AppJwt]
  val installationAccessToken: Gen[Any, InstallationAccessToken] =
    DeriveGen[InstallationAccessToken]

  // --- Enums -----------------------------------------------------------------------------

  val webhookOutcome: Gen[Any, WebhookOutcome]         = DeriveGen.gen[WebhookOutcome].derive
  val linkError: Gen[Any, LinkError]                   = DeriveGen.gen[LinkError].derive
  val unlinkError: Gen[Any, UnlinkError]               = DeriveGen.gen[UnlinkError].derive
  val reconcileError: Gen[Any, ReconcileError]         = DeriveGen.gen[ReconcileError].derive
  val webhookError: Gen[Any, WebhookError]             = DeriveGen.gen[WebhookError].derive
  val githubWebhookEvent: Gen[Any, GithubWebhookEvent] =
    DeriveGen.gen[GithubWebhookEvent].derive

  // --- Case classes ----------------------------------------------------------------------

  val webhookHeaders: Gen[Any, WebhookHeaders]           = DeriveGen.gen[WebhookHeaders].derive
  val pendingLinkFlow: Gen[Any, PendingLinkFlow]         = DeriveGen.gen[PendingLinkFlow].derive
  val webhookDelivery: Gen[Any, WebhookDelivery]         = DeriveGen.gen[WebhookDelivery].derive
  val gitHubGatewayConfig: Gen[Any, GitHubGatewayConfig] =
    DeriveGen.gen[GitHubGatewayConfig].derive
