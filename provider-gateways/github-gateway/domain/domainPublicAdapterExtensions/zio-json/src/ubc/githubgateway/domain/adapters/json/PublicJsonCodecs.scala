package ubc.githubgateway.domain.adapters.json

import ubc.githubgateway.domain.*
import zio.json.*
import neotype.interop.ziojson.given

object PublicJsonCodecs:
  // Newtypes — neotype-zio-json interop derives these automatically
  given JsonCodec[InstallationId]   = summon[JsonCodec[InstallationId]]
  given JsonCodec[GhInstallationId] = summon[JsonCodec[GhInstallationId]]
  given JsonCodec[AccountLogin]     = summon[JsonCodec[AccountLogin]]
  given JsonCodec[AccountId]        = summon[JsonCodec[AccountId]]
  given JsonCodec[RepositoryId]     = summon[JsonCodec[RepositoryId]]
  given JsonCodec[GhRepositoryId]   = summon[JsonCodec[GhRepositoryId]]
  given JsonCodec[RepoFullName]     = summon[JsonCodec[RepoFullName]]
  given JsonCodec[LinkState]        = summon[JsonCodec[LinkState]]
  given JsonCodec[InstallUrl]       = summon[JsonCodec[InstallUrl]]
  given JsonCodec[AppId]            = summon[JsonCodec[AppId]]
  given JsonCodec[AppSlug]          = summon[JsonCodec[AppSlug]]
  given JsonCodec[DeliveryId]       = summon[JsonCodec[DeliveryId]]
  given JsonCodec[EventType]        = summon[JsonCodec[EventType]]
  given JsonCodec[EventAction]      = summon[JsonCodec[EventAction]]
  given JsonCodec[CodeChallenge]    = summon[JsonCodec[CodeChallenge]]

  // Enums
  given JsonCodec[AccountType] = JsonCodec[String].transformOrFail(
    s => scala.util.Try(AccountType.valueOf(s)).toEither.left.map(_.getMessage),
    _.toString
  )
  given JsonCodec[InstallationStatus] = JsonCodec[String].transformOrFail(
    s => scala.util.Try(InstallationStatus.valueOf(s)).toEither.left.map(_.getMessage),
    _.toString
  )

  // Case classes
  given JsonCodec[Installation]      = DeriveJsonCodec.gen
  given JsonCodec[LinkedRepo]        = DeriveJsonCodec.gen
  given JsonCodec[LinkInitiation]    = DeriveJsonCodec.gen
  given JsonCodec[ReconcileSummary]  = DeriveJsonCodec.gen
