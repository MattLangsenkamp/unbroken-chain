package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.Task

/** Outbound port for calls to GitHub's REST API made *as the App* (not as a user).
  *
  * All operations require either an [[AppJwt]] (App-level auth) or an [[InstallationAccessToken]] (installation-level
  * auth) — the caller is responsible for minting one with [[InstallationTokenMinter.mintAppJwt]] and (where needed)
  * exchanging via [[createInstallationToken]] before calling.
  */
trait GitHubAppClient:

  /** `GET /app/installations/{installation_id}` — fetch the installation as known to GitHub.
    *
    * The returned [[Installation]] has [[Installation.id]] = [[UnpersistedInstallationId]] as a sentinel meaning "not
    * yet persisted". Callers MUST overwrite this with the local id from
    * [[InstallationRepository.upsertByGhInstallationId]] before exposing it to the API layer.
    *
    * (We elected not to add a separate "GhInstallation" intermediate value type — the sentinel keeps the port narrow at
    * the cost of one trivial code-smell that core service code must handle.)
    */
  def getInstallation(jwt: AppJwt, ghInstallationId: GhInstallationId): Task[Installation]

  /** `GET /installation/{installation_id}/repositories` — fetch the selected-repo set. Requires an installation token
    * (NOT the App JWT).
    */
  def listInstallationRepos(
      token: InstallationAccessToken,
      ghInstallationId: GhInstallationId
  ): Task[List[(GhRepositoryId, RepoFullName)]]

  /** `DELETE /app/installations/{installation_id}` — revoke the installation on GitHub's side.
    *
    * Semantics:
    *   - succeeds on 204
    *   - succeeds on 404 (already gone — idempotent, GitHub's standard behaviour)
    *   - fails the `Task` with a [[Throwable]] on any other error status or transport failure
    *
    * Callers translate the `Throwable` to [[UnlinkError.GitHubFailure]] at the service boundary.
    */
  def deleteInstallation(jwt: AppJwt, ghInstallationId: GhInstallationId): Task[Unit]

  /** `POST /app/installations/{installation_id}/access_tokens` — exchange an App JWT for a short-lived (~1h)
    * installation access token. Tokens are not cached at this layer; the core service is responsible for any caching it
    * wants.
    */
  def createInstallationToken(jwt: AppJwt, ghInstallationId: GhInstallationId): Task[InstallationAccessToken]
