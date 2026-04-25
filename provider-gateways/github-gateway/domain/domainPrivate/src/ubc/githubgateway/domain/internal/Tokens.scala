package ubc.githubgateway.domain.internal

import neotype.*

/** Short-lived RS256 JWT signed with the GitHub App's private key. Used to mint installation
  * tokens; never leaves this service.
  */
object AppJwt extends Newtype[String]
type AppJwt = AppJwt.Type

/** Installation access token minted on demand by exchanging an [[AppJwt]] for a given
  * installation. Never persisted; never leaves this service.
  */
object InstallationAccessToken extends Newtype[String]
type InstallationAccessToken = InstallationAccessToken.Type

/** Base64-encoded AES key sourced from a Kubernetes secret. Used to encrypt PKCE verifiers
  * at rest; never serialised over the wire.
  */
object EncryptionKey extends Newtype[String]
type EncryptionKey = EncryptionKey.Type
