package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.internal.AppJwt
import zio.Task

/** Outbound port for signing GitHub App JWTs.
  *
  * Separated from [[GitHubAppClient]] because JWT signing needs the App's RSA private key —
  * pure JVM crypto, no HTTP. Token *exchange* (JWT → installation token) lives on
  * [[GitHubAppClient.createInstallationToken]].
  */
trait InstallationTokenMinter:

  /** Sign a fresh App-level JWT. The caller controls TTL via the adapter's configuration —
    * GitHub spec caps it at 10 minutes.
    */
  def mintAppJwt(): Task[AppJwt]
