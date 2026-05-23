package ubc.common.securerandom.inmemory

import ubc.common.securerandom.SecureRandom
import zio.*
import zio.test.*

object DeterministicSecureRandomSpec extends ZIOSpecDefault:

  override def spec =
    suite("DeterministicSecureRandom")(
      test("urlSafeRandomString(32) returns a string of the expected base64url length (43 chars)") {
        for
          rng <- ZIO.service[SecureRandom]
          s   <- rng.urlSafeRandomString(32)
        yield assertTrue(s.length == 43)
      },
      test("successive urlSafeRandomString calls return distinct values") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.urlSafeRandomString(32)
          b   <- rng.urlSafeRandomString(32)
        yield assertTrue(a != b)
      },
      test("nextUuid is deterministic from the shared counter (n-th call → UUID(0, n))") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.nextUuid
          b   <- rng.nextUuid
        yield assertTrue(
          a == new java.util.UUID(0L, 1L),
          b == new java.util.UUID(0L, 2L)
        )
      }
    ).provide(DeterministicSecureRandom.layer)
