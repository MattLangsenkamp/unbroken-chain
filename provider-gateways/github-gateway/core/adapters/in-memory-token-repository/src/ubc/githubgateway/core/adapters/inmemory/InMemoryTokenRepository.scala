package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.TokenRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*

// In-memory TokenRepository backed by a Ref — intended for local dev and testing.
// State is not persisted across restarts.
class InMemoryTokenRepository(store: Ref[Map[UserId, InternalToken]]) extends TokenRepository:

  def save(token: InternalToken): Task[Unit] =
    store.update(_.updated(token.userId, token))

  def findByUserId(userId: UserId): Task[Option[InternalToken]] =
    store.get.map(_.get(userId))

  def delete(id: TokenId): Task[Unit] =
    store.update(_.filter { case (_, token) => token.id != id })

object InMemoryTokenRepository:
  val layer: ULayer[TokenRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[UserId, InternalToken]).map(new InMemoryTokenRepository(_))
    )
