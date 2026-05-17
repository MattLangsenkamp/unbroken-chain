package ubc.common.securerandom.javacrypto

import ubc.common.securerandom.SecureRandom
import zio.*

import java.util.Base64

/** Real [[SecureRandom]] adapter backed by `java.security.SecureRandom`.
  *
  * Returns base64url-encoded (no padding) random strings of arbitrary byte length. Common
  * sizings:
  *   - `byteCount = 32` → 43 chars (good for state nonces; matches RFC 7636's PKCE 43-char
  *     minimum)
  *   - `byteCount = 64` → 86 chars (comfortable PKCE verifier; well under the 128-char ceiling)
  */
final class JavaSecureRandomGenerator(rng: java.security.SecureRandom) extends SecureRandom:

  def urlSafeRandomString(byteCount: Int): UIO[String] =
    ZIO.succeed {
      val bytes = new Array[Byte](byteCount)
      rng.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }

object JavaSecureRandomGenerator:

  /** Build a generator from the system's default `java.security.SecureRandom`. */
  val live: ULayer[SecureRandom] =
    ZLayer.succeed(new JavaSecureRandomGenerator(new java.security.SecureRandom()))
