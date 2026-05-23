package ubc.common.securerandom.javacrypto

import ubc.common.securerandom.SecureRandom
import zio.*

import java.util.{Base64, UUID}

/** Real [[SecureRandom]] adapter backed by `java.security.SecureRandom`.
  *
  * Returns base64url-encoded (no padding) random strings of arbitrary byte length. Common sizings:
  *   - `byteCount = 32` → 43 chars (good for state nonces; matches RFC 7636's PKCE 43-char minimum)
  *   - `byteCount = 64` → 86 chars (comfortable PKCE verifier; well under the 128-char ceiling)
  */
final class JavaSecureRandomGenerator(rng: java.security.SecureRandom) extends SecureRandom:

  def urlSafeRandomString(byteCount: Int): UIO[String] =
    ZIO.succeed {
      val bytes = new Array[Byte](byteCount)
      rng.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }

  def nextUuid: UIO[UUID] =
    ZIO.succeed {
      // Build a type-4 UUID from 16 CSPRNG bytes, setting the version/variant bits per
      // RFC 4122 — exactly how the JDK's own UUID.randomUUID() does it, but from our rng.
      val bytes = new Array[Byte](16)
      rng.nextBytes(bytes)
      bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte // version 4
      bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte // variant 2 (IETF)
      var msb = 0L
      var lsb = 0L
      var i   = 0
      while i < 8 do
        msb = (msb << 8) | (bytes(i) & 0xffL)
        i += 1
      while i < 16 do
        lsb = (lsb << 8) | (bytes(i) & 0xffL)
        i += 1
      new UUID(msb, lsb)
    }

object JavaSecureRandomGenerator:

  /** Build a generator from the system's default `java.security.SecureRandom`. */
  val live: ULayer[SecureRandom] =
    ZLayer.succeed(new JavaSecureRandomGenerator(new java.security.SecureRandom()))
