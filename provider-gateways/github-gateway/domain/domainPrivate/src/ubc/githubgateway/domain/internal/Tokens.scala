package ubc.githubgateway.domain.internal

import neotype.*
import ubc.common.sensitive.Sensitive

/** Short-lived RS256 JWT signed with the GitHub App's private key. Used to mint installation
  * tokens; never leaves this service. Marked `Sensitive` so `ActivityEncoder` redacts it.
  */
object AppJwt extends Newtype[String]:
  def sensitive(s: String): AppJwt = Sensitive.tag(AppJwt(s))
type AppJwt = AppJwt.Type & Sensitive

/** Installation access token minted on demand by exchanging an [[AppJwt]] for a given
  * installation. Never persisted; never leaves this service. Marked `Sensitive` so
  * `ActivityEncoder` redacts it.
  */
object InstallationAccessToken extends Newtype[String]:
  def sensitive(s: String): InstallationAccessToken = Sensitive.tag(InstallationAccessToken(s))
type InstallationAccessToken = InstallationAccessToken.Type & Sensitive
