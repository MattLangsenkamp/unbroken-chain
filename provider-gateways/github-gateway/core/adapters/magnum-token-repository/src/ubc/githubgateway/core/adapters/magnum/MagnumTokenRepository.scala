package ubc.githubgateway.core.adapters.magnum

import ubc.githubgateway.core.ports.TokenRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.githubgateway.domain.internal.adapters.magnum.PrivateMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*

// Derive a DbCodec for InternalToken using the codecs imported above.
private given DbCodec[InternalToken] = DbCodec.derived[InternalToken]

class MagnumTokenRepository(xa: TransactorZIO) extends TokenRepository:

  def save(token: InternalToken): Task[Unit] =
    xa.transact {
      sql"""
        INSERT INTO github_tokens (user_id, token, scopes, created_at, expires_at)
        VALUES (${token.userId}, ${token.token}, ${token.scopes}, ${token.createdAt}, ${token.expiresAt})
        ON CONFLICT (user_id) DO UPDATE
          SET token      = EXCLUDED.token,
              scopes     = EXCLUDED.scopes,
              expires_at = EXCLUDED.expires_at
      """.update.run()
    }.unit

  def findByUserId(userId: UserId): Task[Option[InternalToken]] =
    xa.connect {
      sql"""
        SELECT id, user_id, token, scopes, created_at, expires_at
        FROM github_tokens
        WHERE user_id = $userId
      """.query[InternalToken].run().headOption
    }

  def delete(id: TokenId): Task[Unit] =
    xa.transact {
      sql"DELETE FROM github_tokens WHERE id = $id".update.run()
    }.unit

object MagnumTokenRepository:
  val layer: ZLayer[TransactorZIO, Nothing, TokenRepository] =
    ZLayer.fromFunction(new MagnumTokenRepository(_))
