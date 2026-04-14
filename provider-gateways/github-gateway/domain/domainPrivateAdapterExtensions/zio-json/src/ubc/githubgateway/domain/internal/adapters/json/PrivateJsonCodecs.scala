package ubc.githubgateway.domain.internal.adapters.json

import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import neotype.interop.ziojson.given
import zio.json.*

object PrivateJsonCodecs:
  given JsonCodec[UserId]        = summon[JsonCodec[UserId]]
  given JsonCodec[TokenScope]    = summon[JsonCodec[TokenScope]]
  given JsonCodec[InternalToken] = DeriveJsonCodec.gen
