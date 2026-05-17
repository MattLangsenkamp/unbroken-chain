package ubc.githubgateway.domain.internal.adapters.magnum

import ubc.githubgateway.domain.internal.*
import com.augustnagro.magnum.*

/** `DbCodec` instances for private domain newtypes and enums. */
object PrivateMagnumCodecs:

  given DbCodec[WebhookOutcome] =
    DbCodec[String].biMap(s => WebhookOutcome.valueOf(s), _.toString)
