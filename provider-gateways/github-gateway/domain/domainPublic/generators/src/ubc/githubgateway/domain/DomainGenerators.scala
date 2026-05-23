package ubc.githubgateway.domain

import zio.test.Gen
import zio.test.magnolia.DeriveGen

/** ZIO Test generators for the public github-gateway domain. Test-classpath only.
  *
  * Leaf newtypes export a `given DeriveGen` (so generators for containing types resolve them
  * by derivation) plus a named `val` test handle. Case classes and enums derive from those
  * leaves via `DeriveGen.gen` and are exposed as named `val`s.
  */
object DomainGenerators:

  // --- Leaf newtypes ---------------------------------------------------------------------

  given DeriveGen[InstallationId]   = DeriveGen.instance(Gen.long.map(InstallationId(_)))
  given DeriveGen[GhInstallationId] = DeriveGen.instance(Gen.long.map(GhInstallationId(_)))
  given DeriveGen[AccountLogin]     = DeriveGen.instance(Gen.string.map(AccountLogin(_)))
  given DeriveGen[AccountId]        = DeriveGen.instance(Gen.long.map(AccountId(_)))
  given DeriveGen[RepositoryId]     = DeriveGen.instance(Gen.long.map(RepositoryId(_)))
  given DeriveGen[GhRepositoryId]   = DeriveGen.instance(Gen.long.map(GhRepositoryId(_)))
  given DeriveGen[RepoFullName]     = DeriveGen.instance(Gen.string.map(RepoFullName(_)))
  given DeriveGen[LinkState]        = DeriveGen.instance(Gen.string.map(LinkState(_)))
  given DeriveGen[DeliveryId]       = DeriveGen.instance(Gen.string.map(DeliveryId(_)))
  given DeriveGen[EventType]        = DeriveGen.instance(Gen.string.map(EventType(_)))
  given DeriveGen[EventAction]      = DeriveGen.instance(Gen.string.map(EventAction(_)))
  given DeriveGen[InstallUrl]       = DeriveGen.instance(Gen.string.map(InstallUrl(_)))
  given DeriveGen[AppId]            = DeriveGen.instance(Gen.long.map(AppId(_)))
  given DeriveGen[AppSlug]          = DeriveGen.instance(Gen.string.map(AppSlug(_)))

  val installationId: Gen[Any, InstallationId]     = DeriveGen[InstallationId]
  val ghInstallationId: Gen[Any, GhInstallationId] = DeriveGen[GhInstallationId]
  val accountLogin: Gen[Any, AccountLogin]         = DeriveGen[AccountLogin]
  val accountId: Gen[Any, AccountId]               = DeriveGen[AccountId]
  val repositoryId: Gen[Any, RepositoryId]         = DeriveGen[RepositoryId]
  val ghRepositoryId: Gen[Any, GhRepositoryId]     = DeriveGen[GhRepositoryId]
  val repoFullName: Gen[Any, RepoFullName]         = DeriveGen[RepoFullName]
  val linkState: Gen[Any, LinkState]               = DeriveGen[LinkState]
  val deliveryId: Gen[Any, DeliveryId]             = DeriveGen[DeliveryId]
  val eventType: Gen[Any, EventType]               = DeriveGen[EventType]
  val eventAction: Gen[Any, EventAction]           = DeriveGen[EventAction]
  val installUrl: Gen[Any, InstallUrl]             = DeriveGen[InstallUrl]
  val appId: Gen[Any, AppId]                       = DeriveGen[AppId]
  val appSlug: Gen[Any, AppSlug]                   = DeriveGen[AppSlug]

  // --- Enums -----------------------------------------------------------------------------

  val accountType: Gen[Any, AccountType]               = DeriveGen.gen[AccountType].derive
  val installationStatus: Gen[Any, InstallationStatus] = DeriveGen.gen[InstallationStatus].derive

  // --- Case classes ----------------------------------------------------------------------

  val installation: Gen[Any, Installation]       = DeriveGen.gen[Installation].derive
  val linkedRepo: Gen[Any, LinkedRepo]           = DeriveGen.gen[LinkedRepo].derive
  val linkInitiation: Gen[Any, LinkInitiation]   = DeriveGen.gen[LinkInitiation].derive
  val reconcileSummary: Gen[Any, ReconcileSummary] = DeriveGen.gen[ReconcileSummary].derive
