package ubc.githubgateway.domain.internal.adapters.magnum

import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import com.augustnagro.magnum.*

object PrivateMagnumCodecs:
  given DbCodec[UserId]     = DbCodec[String].imap(UserId.apply)(_.value)
  given DbCodec[TokenScope] = DbCodec[String].imap(TokenScope.apply)(_.value)
