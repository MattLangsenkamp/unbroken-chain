package ubc.githubgateway.domain.internal.adapters.json

import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.json.PublicJsonCodecs.given
import zio.json.*

object PrivateJsonCodecs:
  given JsonCodec[UserId]        = DeriveJsonCodec.gen
  given JsonCodec[TokenScope]    = DeriveJsonCodec.gen
  given JsonCodec[InternalToken] = DeriveJsonCodec.gen
