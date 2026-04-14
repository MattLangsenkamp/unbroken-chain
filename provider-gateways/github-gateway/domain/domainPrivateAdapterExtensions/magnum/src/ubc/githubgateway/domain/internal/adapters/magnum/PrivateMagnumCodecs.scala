package ubc.githubgateway.domain.internal.adapters.magnum

import ubc.githubgateway.domain.internal.*
import UserId.given, TokenScope.given
import com.augustnagro.magnum.*
import neotype.*

object PrivateMagnumCodecs:
  // neotype opaque types don't have Magnum Mirror integration; biMap is the only option.
  given DbCodec[UserId]     = DbCodec[String].biMap(UserId(_), _.unwrap)
  given DbCodec[TokenScope] = DbCodec[String].biMap(TokenScope(_), _.unwrap)

  // Comma-separated encoding for List[TokenScope] — no native ARRAY type required.
  given DbCodec[List[TokenScope]] =
    DbCodec[String].biMap(
      s => s.split(",").toList.filter(_.nonEmpty).map(TokenScope(_)),
      _.map(_.unwrap).mkString(",")
    )
