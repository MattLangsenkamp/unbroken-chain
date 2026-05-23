package ubc.githubgateway.core.adapters.inmemory

import neotype.*
import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.core.ports.LinkedRepoRepository
import ubc.githubgateway.domain.*
import zio.*

import java.util.UUID

/** Ref-backed [[LinkedRepoRepository]] for tests and local dev.
  *
  * State is a single map keyed by the local [[RepositoryId]] (a UUID we generate on insert).
  * `listByInstallation` / `listAll` return a stable order (by id string) — not insertion order,
  * matching the UUID-keyed Postgres adapter's `ORDER BY id`.
  */
final class InMemoryLinkedRepoRepository(
    store: Ref[Map[RepositoryId, LinkedRepo]]
) extends LinkedRepoRepository:

  private def nextRow(
      installationId: InstallationId,
      ghRepositoryId: GhRepositoryId,
      fullName: RepoFullName
  ): UIO[LinkedRepo] =
    ZIO.succeed(LinkedRepo(RepositoryId(UUID.randomUUID()), installationId, ghRepositoryId, fullName))

  def insert(
      installationId: InstallationId,
      ghRepositoryId: GhRepositoryId,
      fullName: RepoFullName
  ): Task[LinkedRepo] =
    for
      row <- nextRow(installationId, ghRepositoryId, fullName)
      _   <- store.update(_.updated(row.id, row))
    yield row

  def replaceSet(
      installationId: InstallationId,
      desired: List[(GhRepositoryId, RepoFullName)]
  ): Task[ReconcileSummary] =
    store.get.flatMap { current =>
      val owned       = current.values.filter(_.installationId == installationId).toList
      val ownedByGhId = owned.map(r => r.ghRepositoryId -> r).toMap
      val desiredMap  = desired.toMap

      val toRemove    = owned.filterNot(r => desiredMap.contains(r.ghRepositoryId))
      val (toRename, toInsert) = desired.partition { case (ghId, _) => ownedByGhId.contains(ghId) }
      val renames     = toRename.collect {
        case (ghId, name) if ownedByGhId(ghId).fullName != name =>
          ownedByGhId(ghId).copy(fullName = name)
      }

      for
        // delete unselected
        _ <- store.update(s => toRemove.foldLeft(s)((acc, r) => acc - r.id))
        // apply renames
        _ <- store.update(s => renames.foldLeft(s)((acc, r) => acc.updated(r.id, r)))
        // insert new
        inserted <- ZIO.foreach(toInsert) { case (ghId, name) =>
                      nextRow(installationId, ghId, name)
                    }
        _ <- store.update(s => inserted.foldLeft(s)((acc, r) => acc.updated(r.id, r)))
      yield ReconcileSummary(
        added   = inserted.size,
        removed = toRemove.size,
        renamed = renames.size
      )
    }

  def listByInstallation(installationId: InstallationId, page: PageRequest): Task[Page[LinkedRepo]] =
    store.get.map { current =>
      val owned = current.values
        .filter(_.installationId == installationId)
        .toList
        .sortBy(_.id.unwrap.toString)
      Page(items = owned.take(page.limit), total = owned.size.toLong, nextCursor = None)
    }

  def listAll(page: PageRequest): Task[Page[LinkedRepo]] =
    store.get.map { current =>
      val ordered = current.values.toList.sortBy(_.id.unwrap.toString)
      Page(items = ordered.take(page.limit), total = ordered.size.toLong, nextCursor = None)
    }

  def renameByGhRepositoryId(ghRepositoryId: GhRepositoryId, newFullName: RepoFullName): Task[Unit] =
    store.update { current =>
      current.values.find(_.ghRepositoryId == ghRepositoryId) match
        case Some(row) => current.updated(row.id, row.copy(fullName = newFullName))
        case None      => current
    }

  def insertMany(
      installationId: InstallationId,
      repos: List[(GhRepositoryId, RepoFullName)]
  ): Task[Unit] =
    ZIO.foreachDiscard(repos) { case (ghId, name) =>
      insert(installationId, ghId, name)
    }

  def deleteByGhRepositoryIds(
      installationId: InstallationId,
      ghRepositoryIds: List[GhRepositoryId]
  ): Task[Unit] =
    val targets = ghRepositoryIds.toSet
    store.update { current =>
      current.filterNot { case (_, row) =>
        row.installationId == installationId && targets.contains(row.ghRepositoryId)
      }
    }

object InMemoryLinkedRepoRepository:
  val layer: ULayer[LinkedRepoRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[RepositoryId, LinkedRepo]).map(new InMemoryLinkedRepoRepository(_))
    )
