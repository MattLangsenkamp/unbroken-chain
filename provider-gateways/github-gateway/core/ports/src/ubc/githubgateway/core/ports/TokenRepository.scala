package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.Task

trait TokenRepository:
  def save(token: InternalToken): Task[Unit]
  def findByUserId(userId: UserId): Task[Option[InternalToken]]
  def delete(id: TokenId): Task[Unit]
