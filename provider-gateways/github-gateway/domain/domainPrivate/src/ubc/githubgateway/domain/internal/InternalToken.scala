package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.{GitHubToken, TokenId}
import java.time.Instant

case class UserId(value: String)
case class TokenScope(value: String)

case class InternalToken(
  id: TokenId,
  userId: UserId,
  token: GitHubToken,
  scopes: List[TokenScope],
  createdAt: Instant,
  expiresAt: Option[Instant]
)
