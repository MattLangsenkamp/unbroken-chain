package ubc.githubgateway.domain.internal.adapters.magnum

import ubc.githubgateway.domain.internal.*
import com.augustnagro.magnum.*

// Private types map directly to primitives — no dependency on PublicMagnumCodecs needed.
object PrivateMagnumCodecs:
  given DbCodec[UserId]     = DbCodec[String].biMap(UserId.apply, _.value)
  given DbCodec[TokenScope] = DbCodec[String].biMap(TokenScope.apply, _.value)
