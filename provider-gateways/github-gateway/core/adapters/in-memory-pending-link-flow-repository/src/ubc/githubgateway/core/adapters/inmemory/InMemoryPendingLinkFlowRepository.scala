package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.LinkState
import ubc.githubgateway.domain.internal.PendingLinkFlow
import zio.*

import java.time.Instant

/** Ref-backed [[PendingLinkFlowRepository]] for tests and local dev.
  *
  * State is keyed by [[LinkState]] — the same uniqueness constraint Postgres enforces.
  */
final class InMemoryPendingLinkFlowRepository(
    store: Ref[Map[LinkState, PendingLinkFlow]]
) extends PendingLinkFlowRepository:

  def insert(flow: PendingLinkFlow): Task[Unit] =
    store.update(_.updated(flow.state, flow))

  def findByState(state: LinkState): Task[Option[PendingLinkFlow]] =
    store.get.map(_.get(state))

  def deleteByState(state: LinkState): Task[Boolean] =
    store.modify { current =>
      if current.contains(state) then (true, current - state)
      else (false, current)
    }

  def deleteExpired(now: Instant): Task[Int] =
    store.modify { current =>
      val expired = current.filter { case (_, flow) => !flow.expiresAt.isAfter(now) }
      (expired.size, current -- expired.keys)
    }

object InMemoryPendingLinkFlowRepository:
  val layer: ULayer[PendingLinkFlowRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[LinkState, PendingLinkFlow]).map(new InMemoryPendingLinkFlowRepository(_))
    )
