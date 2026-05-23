package ubc.githubgateway.core.adapters.magnum

import ubc.common.magnum.MagnumPagination
import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.core.ports.LinkedRepoRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*

import java.util.UUID

private given DbCodec[LinkedRepo] = DbCodec.derived[LinkedRepo]

class MagnumLinkedRepoRepository(xa: TransactorZIO) extends LinkedRepoRepository:

  def insert(
      installationId: InstallationId,
      ghRepositoryId: GhRepositoryId,
      fullName: RepoFullName
  ): Task[LinkedRepo] =
    xa.transact {
      val id = RepositoryId(UUID.randomUUID())
      sql"""
        INSERT INTO linked_repo (id, installation_id, gh_repository_id, full_name)
        VALUES ($id, $installationId, $ghRepositoryId, $fullName)
        RETURNING id, installation_id, gh_repository_id, full_name
      """.returning[LinkedRepo].run().head
    }

  def replaceSet(
      installationId: InstallationId,
      desired: List[(GhRepositoryId, RepoFullName)]
  ): Task[ReconcileSummary] =
    xa.transact {
      val existing = sql"""
        SELECT id, installation_id, gh_repository_id, full_name
        FROM linked_repo
        WHERE installation_id = $installationId
      """.query[LinkedRepo].run().toList

      val existingByGhId = existing.map(r => r.ghRepositoryId -> r).toMap
      val desiredMap     = desired.toMap
      val desiredIds     = desiredMap.keySet

      val toRemove = existing.filterNot(r => desiredIds.contains(r.ghRepositoryId))
      val toInsert = desired.filterNot { case (ghId, _) => existingByGhId.contains(ghId) }
      val toRename = desired.collect {
        case (ghId, name) if existingByGhId.get(ghId).exists(_.fullName != name) =>
          (ghId, name)
      }

      // Apply deletes
      toRemove.foreach { row =>
        sql"DELETE FROM linked_repo WHERE id = ${row.id}".update.run()
      }
      // Apply renames
      toRename.foreach { case (ghId, newName) =>
        sql"""UPDATE linked_repo SET full_name = $newName
              WHERE installation_id = $installationId AND gh_repository_id = $ghId""".update.run()
      }
      // Apply inserts
      toInsert.foreach { case (ghId, name) =>
        val id = RepositoryId(UUID.randomUUID())
        sql"""INSERT INTO linked_repo (id, installation_id, gh_repository_id, full_name)
              VALUES ($id, $installationId, $ghId, $name)""".update.run()
      }

      ReconcileSummary(
        added   = toInsert.size,
        removed = toRemove.size,
        renamed = toRename.size
      )
    }

  def listByInstallation(installationId: InstallationId, page: PageRequest): Task[Page[LinkedRepo]] =
    val offset = MagnumPagination.cursorToOffset(page.cursor)
    val limit  = page.limit
    xa.transact {
      val items = sql"""
        SELECT id, installation_id, gh_repository_id, full_name
        FROM linked_repo
        WHERE installation_id = $installationId
        ORDER BY id ASC
        LIMIT $limit OFFSET $offset
      """.query[LinkedRepo].run().toList
      val total = sql"SELECT COUNT(*) FROM linked_repo WHERE installation_id = $installationId"
        .query[Long].run().head
      Page(
        items      = items,
        total      = total,
        nextCursor = MagnumPagination.nextCursor(offset, items.size, total)
      )
    }

  def listAll(page: PageRequest): Task[Page[LinkedRepo]] =
    val offset = MagnumPagination.cursorToOffset(page.cursor)
    val limit  = page.limit
    xa.transact {
      val items = sql"""
        SELECT id, installation_id, gh_repository_id, full_name
        FROM linked_repo
        ORDER BY id ASC
        LIMIT $limit OFFSET $offset
      """.query[LinkedRepo].run().toList
      val total = sql"SELECT COUNT(*) FROM linked_repo".query[Long].run().head
      Page(
        items      = items,
        total      = total,
        nextCursor = MagnumPagination.nextCursor(offset, items.size, total)
      )
    }

  def renameByGhRepositoryId(ghRepositoryId: GhRepositoryId, newFullName: RepoFullName): Task[Unit] =
    xa.transact {
      sql"UPDATE linked_repo SET full_name = $newFullName WHERE gh_repository_id = $ghRepositoryId"
        .update.run()
    }.unit

  def insertMany(
      installationId: InstallationId,
      repos: List[(GhRepositoryId, RepoFullName)]
  ): Task[Unit] =
    if repos.isEmpty then ZIO.unit
    else
      xa.transact {
        repos.foreach { case (ghId, name) =>
          val id = RepositoryId(UUID.randomUUID())
          sql"""INSERT INTO linked_repo (id, installation_id, gh_repository_id, full_name)
                VALUES ($id, $installationId, $ghId, $name)""".update.run()
        }
      }.unit

  def deleteByGhRepositoryIds(
      installationId: InstallationId,
      ghRepositoryIds: List[GhRepositoryId]
  ): Task[Unit] =
    if ghRepositoryIds.isEmpty then ZIO.unit
    else
      xa.transact {
        // Iterate within a single transaction. A `WHERE id = ANY(...)` form would require a
        // Postgres-typed Long[] array which Magnum cannot bind through PreparedStatement
        // without a custom codec. A small loop is simpler and the cardinality is bounded
        // (one webhook payload's removed repos).
        ghRepositoryIds.foreach { ghId =>
          sql"""DELETE FROM linked_repo
                WHERE installation_id = $installationId AND gh_repository_id = $ghId""".update.run()
        }
      }.unit

object MagnumLinkedRepoRepository:
  val layer: ZLayer[TransactorZIO, Nothing, LinkedRepoRepository] =
    ZLayer.fromFunction(new MagnumLinkedRepoRepository(_))
