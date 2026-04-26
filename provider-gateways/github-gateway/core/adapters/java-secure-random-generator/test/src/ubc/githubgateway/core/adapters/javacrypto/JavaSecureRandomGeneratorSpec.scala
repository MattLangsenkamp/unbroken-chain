package ubc.githubgateway.core.adapters.javacrypto

import neotype.*
import ubc.githubgateway.core.ports.SecureRandom
import zio.*
import zio.test.*

object JavaSecureRandomGeneratorSpec extends ZIOSpecDefault:

  // RFC 7636 §4.1: code_verifier is drawn from [A-Z][a-z][0-9]-._~. We use base64url
  // (without padding) which is the same charset modulo `.~` so the regex below is
  // [A-Za-z0-9_-].
  private val urlSafeCharset = "^[A-Za-z0-9_-]+$".r

  override def spec =
    suite("JavaSecureRandomGenerator")(
      test("newCodeVerifier returns 43-128 URL-safe characters per RFC 7636") {
        for
          rng <- ZIO.service[SecureRandom]
          v   <- rng.newCodeVerifier()
          s   = v.unwrap
        yield assertTrue(s.length >= 43, s.length <= 128)
      },
      test("newCodeVerifier output matches the URL-safe charset [A-Za-z0-9_-]") {
        for
          rng <- ZIO.service[SecureRandom]
          v   <- rng.newCodeVerifier()
        yield assertTrue(urlSafeCharset.matches(v.unwrap))
      },
      test("newLinkState returns a non-empty URL-safe string") {
        for
          rng <- ZIO.service[SecureRandom]
          s   <- rng.newLinkState()
        yield assertTrue(s.unwrap.nonEmpty, urlSafeCharset.matches(s.unwrap))
      },
      test("two consecutive newCodeVerifier calls return different values") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.newCodeVerifier()
          b   <- rng.newCodeVerifier()
        yield assertTrue(a != b)
      },
      test("two consecutive newLinkState calls return different values") {
        for
          rng <- ZIO.service[SecureRandom]
          a   <- rng.newLinkState()
          b   <- rng.newLinkState()
        yield assertTrue(a != b)
      }
    ).provide(JavaSecureRandomGenerator.live)
