package ubc.githubgateway.core.adapters.magnum

import ubc.githubgateway.core.ports.TokenRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.githubgateway.domain.internal.adapters.magnum.PrivateMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*
import javax.sql.DataSource
import java.time.Instant

// Flat row type used for DB reads and writes.
// `derives DbCodec` picks up the given DbCodec instances imported above.
case class TokenRow(
  id: Long,
  userId: String,
  token: String,
  scopes: String, // comma-separated scope values
  createdAt: Instant,
  expiresAt: Option[Instant]
) derives DbCodec

object TokenRow:
  def fromDomain(t: InternalToken): TokenRow =
    TokenRow(
      id = t.id.value,
      userId = t.userId.value,
      token = t.token.value,
      scopes = t.scopes.map(_.value).mkString(","),
      createdAt = t.createdAt,
      expiresAt = t.expiresAt
    )

  extension (row: TokenRow)
    def toDomain: InternalToken =
      InternalToken(
        id = TokenId(row.id),
        userId = UserId(row.userId),
        token = GitHubToken(row.token),
        scopes = row.scopes.split(",").toList.filter(_.nonEmpty).map(TokenScope.apply),
        createdAt = row.createdAt,
        expiresAt = row.expiresAt
      )

class MagnumTokenRepository(ds: DataSource) extends TokenRepository:

  def save(token: InternalToken): Task[Unit] =
    val row = TokenRow.fromDomain(token)
    transact(ds) {
      sql"""
        INSERT INTO github_tokens (user_id, token, scopes, created_at, expires_at)
        VALUES (${row.userId}, ${row.token}, ${row.scopes}, ${row.createdAt}, ${row.expiresAt})
        ON CONFLICT (user_id) DO UPDATE
          SET token      = EXCLUDED.token,
              scopes     = EXCLUDED.scopes,
              expires_at = EXCLUDED.expires_at
      """.update.run()
    }.unit

  def findByUserId(userId: UserId): Task[Option[InternalToken]] =
    connect(ds) {
      sql"""
        SELECT id, user_id, token, scopes, created_at, expires_at
        FROM github_tokens
        WHERE user_id = ${userId.value}
      """.query[TokenRow].option()
    }.map(_.map(_.toDomain))

  def delete(id: TokenId): Task[Unit] =
    transact(ds) {
      sql"DELETE FROM github_tokens WHERE id = ${id.value}".update.run()
    }.unit

object MagnumTokenRepository:
  val layer: ZLayer[DataSource, Nothing, TokenRepository] =
    ZLayer.fromFunction(new MagnumTokenRepository(_))
