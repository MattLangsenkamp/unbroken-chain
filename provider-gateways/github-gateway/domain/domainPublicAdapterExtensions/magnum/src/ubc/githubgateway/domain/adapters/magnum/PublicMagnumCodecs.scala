package ubc.githubgateway.domain.adapters.magnum

import ubc.githubgateway.domain.*
import com.augustnagro.magnum.*
import neotype.*

import java.util.UUID

/** `DbCodec` instances for public domain newtypes and enums.
  *
  * Newtypes use an explicit `biMap` on a primitive `DbCodec` — Magnum cannot derive
  * codecs for opaque neotype types automatically. Enums are encoded as TEXT and decoded
  * by `valueOf` (the case object name).
  */
object PublicMagnumCodecs:

  // Identifiers we own — UUID-backed (see the relational-database-modeling skill).
  given DbCodec[InstallationId] = DbCodec[UUID].biMap(InstallationId(_), _.unwrap)
  given DbCodec[RepositoryId]   = DbCodec[UUID].biMap(RepositoryId(_), _.unwrap)
  given DbCodec[LinkState]      = DbCodec[UUID].biMap(LinkState(_), _.unwrap)
  given DbCodec[DeliveryId]     = DbCodec[UUID].biMap(DeliveryId(_), _.unwrap)

  // Third-party numeric identifiers GitHub assigns — Long-backed.
  given DbCodec[GhInstallationId] = DbCodec[Long].biMap(GhInstallationId(_), _.unwrap)
  given DbCodec[AccountId]        = DbCodec[Long].biMap(AccountId(_), _.unwrap)
  given DbCodec[GhRepositoryId]   = DbCodec[Long].biMap(GhRepositoryId(_), _.unwrap)

  // String newtypes — wrap/unwrap a String.
  given DbCodec[AccountLogin]     = DbCodec[String].biMap(AccountLogin(_), _.unwrap)
  given DbCodec[RepoFullName]     = DbCodec[String].biMap(RepoFullName(_), _.unwrap)
  given DbCodec[EventType]        = DbCodec[String].biMap(EventType(_), _.unwrap)

  // Enums — encode as TEXT, decode by case name.
  given DbCodec[AccountType] =
    DbCodec[String].biMap(s => AccountType.valueOf(s), _.toString)
  given DbCodec[InstallationStatus] =
    DbCodec[String].biMap(s => InstallationStatus.valueOf(s), _.toString)
