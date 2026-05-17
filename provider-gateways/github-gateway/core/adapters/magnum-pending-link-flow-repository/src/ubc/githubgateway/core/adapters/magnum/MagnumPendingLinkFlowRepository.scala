package ubc.githubgateway.core.adapters.magnum

import ubc.githubgateway.core.ports.PendingLinkFlowRepository
import ubc.githubgateway.domain.LinkState
import ubc.githubgateway.domain.internal.PendingLinkFlow
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*

import java.time.Instant

private given DbCodec[PendingLinkFlow] = DbCodec.derived[PendingLinkFlow]

class MagnumPendingLinkFlowRepository(xa: TransactorZIO) extends PendingLinkFlowRepository:

  def insert(flow: PendingLinkFlow): Task[Unit] =
    xa.transact {
      sql"""
        INSERT INTO pending_link_flow (state, created_at, expires_at)
        VALUES (${flow.state}, ${flow.createdAt}, ${flow.expiresAt})
      """.update.run()
    }.unit

  def findByState(state: LinkState): Task[Option[PendingLinkFlow]] =
    xa.connect {
      sql"""
        SELECT state, created_at, expires_at
        FROM pending_link_flow
        WHERE state = $state
      """.query[PendingLinkFlow].run().headOption
    }

  def deleteByState(state: LinkState): Task[Boolean] =
    xa.transact {
      sql"DELETE FROM pending_link_flow WHERE state = $state".update.run()
    }.map(_ > 0)

  def deleteExpired(now: Instant): Task[Int] =
    xa.transact {
      sql"DELETE FROM pending_link_flow WHERE expires_at <= $now".update.run()
    }

object MagnumPendingLinkFlowRepository:
  val layer: ZLayer[TransactorZIO, Nothing, PendingLinkFlowRepository] =
    ZLayer.fromFunction(new MagnumPendingLinkFlowRepository(_))
