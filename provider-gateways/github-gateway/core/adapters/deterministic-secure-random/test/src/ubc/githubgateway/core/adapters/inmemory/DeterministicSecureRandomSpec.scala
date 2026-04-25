package ubc.githubgateway.core.adapters.inmemory

import neotype.*
import ubc.githubgateway.core.ports.SecureRandom
import zio.*
import zio.test.*

object DeterministicSecureRandomSpec extends ZIOSpecDefault:

  override def spec =
    suite("DeterministicSecureRandom")(
      test("newCodeVerifier returns a string of at least 43 characters (RFC 7636 minimum)") {
        for
          rng <- ZIO.service[SecureRandom]
          v   <- rng.newCodeVerifier()
        yield assertTrue(v.unwrap.length >= 43)
      },
      test("successive newCodeVerifier calls return distinct values") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.newCodeVerifier()
          b   <- rng.newCodeVerifier()
        yield assertTrue(a != b)
      },
      test("successive newLinkState calls return distinct values") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.newLinkState()
          b   <- rng.newLinkState()
        yield assertTrue(a != b)
      }
    ).provide(DeterministicSecureRandom.layer)
