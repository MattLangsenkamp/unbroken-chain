package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.{GitHubToken, TokenId}
import neotype.*
import java.time.Instant

object UserId extends Newtype[String]
type UserId = UserId.Type

object TokenScope extends Newtype[String]
type TokenScope = TokenScope.Type

case class InternalToken(
  id: TokenId,
  userId: UserId,
  token: GitHubToken,
  scopes: List[TokenScope],
  createdAt: Instant,
  expiresAt: Option[Instant]
)
