package ubc.githubgateway.core.adapters.inmemory

import neotype.*
import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.core.ports.InstallationRepository
import ubc.githubgateway.domain.*
import zio.*

import java.time.Instant

/** Ref-backed [[InstallationRepository]] for tests and local dev.
  *
  * State is two refs: a counter for assigning local ids on insert, and a map keyed by the
  * local [[InstallationId]]. We key on the local id (not [[GhInstallationId]]) so the order
  * of `listAll` is stable insertion order — matching the Postgres adapter's `ORDER BY id`.
  *
  * NOTE: deletes do NOT cascade to linked-repo rows. The Postgres adapter relies on a DB
  * cascade; tests using both repositories must clean up linked rows explicitly.
  */
final class InMemoryInstallationRepository(
    counter: Ref[Long],
    store: Ref[Map[InstallationId, Installation]]
) extends InstallationRepository:

  def upsertByGhInstallationId(
      ghInstallationId: GhInstallationId,
      accountLogin: AccountLogin,
      accountId: AccountId,
      accountType: AccountType,
      status: InstallationStatus,
      installedAt: Instant
  ): Task[Installation] =
    store.get.flatMap { current =>
      current.values.find(_.ghInstallationId == ghInstallationId) match
        case Some(existing) =>
          val updated = existing.copy(
            accountLogin = accountLogin,
            accountId    = accountId,
            accountType  = accountType,
            status       = status,
            installedAt  = installedAt
          )
          store.update(_.updated(existing.id, updated)).as(updated)
        case None =>
          for
            nextId <- counter.updateAndGet(_ + 1L)
            id      = InstallationId(nextId)
            inst    = Installation(
                        id               = id,
                        ghInstallationId = ghInstallationId,
                        accountLogin     = accountLogin,
                        accountId        = accountId,
                        accountType      = accountType,
                        status           = status,
                        installedAt      = installedAt
                      )
            _      <- store.update(_.updated(id, inst))
          yield inst
    }

  def findByGhInstallationId(ghInstallationId: GhInstallationId): Task[Option[Installation]] =
    store.get.map(_.values.find(_.ghInstallationId == ghInstallationId))

  def listAll(page: PageRequest): Task[Page[Installation]] =
    store.get.map { current =>
      val ordered = current.values.toList.sortBy(_.id.unwrap)
      val limited = ordered.take(page.limit)
      Page(items = limited, total = ordered.size.toLong, nextCursor = None)
    }

  def deleteByGhInstallationId(ghInstallationId: GhInstallationId): Task[Boolean] =
    store.modify { current =>
      current.values.find(_.ghInstallationId == ghInstallationId) match
        case Some(existing) => (true, current - existing.id)
        case None           => (false, current)
    }

  def updateStatus(ghInstallationId: GhInstallationId, status: InstallationStatus): Task[Unit] =
    store.update { current =>
      current.values.find(_.ghInstallationId == ghInstallationId) match
        case Some(existing) => current.updated(existing.id, existing.copy(status = status))
        case None           => current
    }

object InMemoryInstallationRepository:
  val layer: ULayer[InstallationRepository] =
    ZLayer.fromZIO(
      for
        counter <- Ref.make(0L)
        store   <- Ref.make(Map.empty[InstallationId, Installation])
      yield new InMemoryInstallationRepository(counter, store)
    )
