package ubc.githubgateway.core.ports

import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.domain.*
import zio.Task

import java.time.Instant

/** Persistence port for [[ubc.githubgateway.domain.Installation]] rows.
  *
  * Identity for this aggregate is GitHub's `installation_id` ([[GhInstallationId]]) — the local
  * surrogate [[InstallationId]] is an implementation detail of the adapter. All write paths key
  * off the GitHub id so webhook handlers and reconcilers don't need to look anything up first.
  */
trait InstallationRepository:

  /** Insert a new installation, or update the existing row keyed by [[GhInstallationId]].
    * Returns the resulting installation (with assigned local id on insert, preserved on update).
    */
  def upsertByGhInstallationId(
      ghInstallationId: GhInstallationId,
      accountLogin: AccountLogin,
      accountId: AccountId,
      accountType: AccountType,
      status: InstallationStatus,
      installedAt: Instant
  ): Task[Installation]

  /** Look up an installation by GitHub's id. Returns `None` if no row matches. */
  def findByGhInstallationId(ghInstallationId: GhInstallationId): Task[Option[Installation]]

  /** List installations in a stable order (by local id ascending). */
  def listAll(page: PageRequest): Task[Page[Installation]]

  /** Delete the installation row keyed by GitHub's id.
    *
    * Cascade of `linked_repo` rows is the **adapter's** responsibility — Postgres uses an
    * `ON DELETE CASCADE` foreign key, while the in-memory stub does NOT cascade automatically
    * (tests that need clean state must delete linked_repo rows explicitly via the
    * [[LinkedRepoRepository]]). Document this caveat in callers.
    *
    * @return `true` if a row was deleted, `false` if no matching row existed.
    */
  def deleteByGhInstallationId(ghInstallationId: GhInstallationId): Task[Boolean]

  /** Update only the `status` field of the row keyed by [[GhInstallationId]].
    * No-op if no row matches.
    */
  def updateStatus(ghInstallationId: GhInstallationId, status: InstallationStatus): Task[Unit]
