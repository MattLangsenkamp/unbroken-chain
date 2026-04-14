package ubc.githubgateway.domain.internal.adapters.magnum

import ubc.githubgateway.domain.internal.*
import com.augustnagro.magnum.*

object PrivateMagnumCodecs:
  given DbCodec[UserId]     = DbCodec.derived[UserId]
  given DbCodec[TokenScope] = DbCodec.derived[TokenScope]

  // Comma-separated encoding for List[TokenScope] — no native ARRAY type required.
  given DbCodec[List[TokenScope]] =
    DbCodec[String].biMap(
      s => s.split(",").toList.filter(_.nonEmpty).map(TokenScope.apply),
      _.map(_.value).mkString(",")
    )
