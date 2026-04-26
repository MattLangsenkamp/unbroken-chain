package ubc.githubgateway.domain.internal.adapters.magnum

import ubc.githubgateway.domain.internal.*
import com.augustnagro.magnum.*
import neotype.*

/** `DbCodec` instances for private domain newtypes and enums.
  *
  * Notable: [[EncryptedBytes]] is a `Newtype[String]` (base64 at the domain level) but the
  * column is `BYTEA`. The codec decodes raw bytes from BYTEA and base64-encodes for the
  * domain; on write it base64-decodes back to bytes.
  */
object PrivateMagnumCodecs:

  // BYTEA <-> base64 String. Domain level keeps base64 to preserve value-equality semantics.
  given DbCodec[EncryptedBytes] =
    DbCodec[Array[Byte]].biMap(
      bytes => EncryptedBytes(java.util.Base64.getEncoder.encodeToString(bytes)),
      enc   => java.util.Base64.getDecoder.decode(enc.unwrap)
    )

  given DbCodec[WebhookOutcome] =
    DbCodec[String].biMap(s => WebhookOutcome.valueOf(s), _.toString)
