package ubc.common.securerandom.javacrypto

import ubc.common.securerandom.SecureRandom
import zio.*
import zio.test.*

object JavaSecureRandomGeneratorSpec extends ZIOSpecDefault:

  // base64url (no padding) charset is [A-Za-z0-9_-].
  private val urlSafeCharset = "^[A-Za-z0-9_-]+$".r

  override def spec =
    suite("JavaSecureRandomGenerator")(
      test("urlSafeRandomString(32) returns a 43-char URL-safe string") {
        for
          rng <- ZIO.service[SecureRandom]
          s   <- rng.urlSafeRandomString(32)
        yield assertTrue(s.length == 43, urlSafeCharset.matches(s))
      },
      test("urlSafeRandomString(64) returns an 86-char URL-safe string") {
        for
          rng <- ZIO.service[SecureRandom]
          s   <- rng.urlSafeRandomString(64)
        yield assertTrue(s.length == 86, urlSafeCharset.matches(s))
      },
      test("two consecutive calls with the same byteCount return different values") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.urlSafeRandomString(32)
          b   <- rng.urlSafeRandomString(32)
        yield assertTrue(a != b)
      },
      test("nextUuid returns distinct version-4 UUIDs") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.nextUuid
          b   <- rng.nextUuid
        yield assertTrue(a != b, a.version == 4, b.version == 4, a.variant == 2)
      }
    ).provide(JavaSecureRandomGenerator.live)
