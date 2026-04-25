package ubc.githubgateway.core.ports

import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.domain.*
import zio.Task

/** Persistence port for [[ubc.githubgateway.domain.LinkedRepo]] rows.
  *
  * Each row is owned by an [[Installation]] (via local [[InstallationId]] FK) and identified
  * within that installation by GitHub's stable [[GhRepositoryId]] (which survives renames /
  * transfers; `fullName` does not).
  */
trait LinkedRepoRepository:

  /** Insert a single linked-repo row. Caller supplies the installation's local id (NOT the
    * GitHub installation id) — the FK is internal to our schema.
    */
  def insert(
      installationId: InstallationId,
      ghRepositoryId: GhRepositoryId,
      fullName: RepoFullName
  ): Task[LinkedRepo]

  /** Atomically replace the entire selected-repo set for an installation:
    *   - rows present in `desired` but missing locally are inserted
    *   - rows missing from `desired` but present locally are deleted
    *   - rows whose `ghRepositoryId` matches but whose `fullName` differs are updated in place
    *
    * @return counts of inserted / removed / renamed rows
    */
  def replaceSet(
      installationId: InstallationId,
      desired: List[(GhRepositoryId, RepoFullName)]
  ): Task[ReconcileSummary]

  /** List linked repos for one installation, paginated. */
  def listByInstallation(installationId: InstallationId, page: PageRequest): Task[Page[LinkedRepo]]

  /** List linked repos across all installations, paginated. */
  def listAll(page: PageRequest): Task[Page[LinkedRepo]]

  /** Update `fullName` in place for the row identified by GitHub's stable repository id.
    * Used by `repository.renamed` and `repository.transferred` webhook events.
    * No-op if no row matches.
    */
  def renameByGhRepositoryId(ghRepositoryId: GhRepositoryId, newFullName: RepoFullName): Task[Unit]

  /** Insert multiple rows for one installation. Used by `installation_repositories.added`. */
  def insertMany(
      installationId: InstallationId,
      repos: List[(GhRepositoryId, RepoFullName)]
  ): Task[Unit]

  /** Delete rows by GitHub repository ids within a single installation. Used by
    * `installation_repositories.removed`.
    */
  def deleteByGhRepositoryIds(
      installationId: InstallationId,
      ghRepositoryIds: List[GhRepositoryId]
  ): Task[Unit]
