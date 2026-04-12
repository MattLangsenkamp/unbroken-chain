package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.*

trait TokenRepository:
  def save(token: InternalToken): Task[Unit]
  def findByUserId(userId: UserId): Task[Option[InternalToken]]
  def delete(id: TokenId): Task[Unit]

object TokenRepository:
  def save(token: InternalToken): ZIO[TokenRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[TokenRepository](_.save(token))
  def findByUserId(userId: UserId): ZIO[TokenRepository, Throwable, Option[InternalToken]] =
    ZIO.serviceWithZIO[TokenRepository](_.findByUserId(userId))
  def delete(id: TokenId): ZIO[TokenRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[TokenRepository](_.delete(id))
